package com.techandthat.slpmealselection

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.activity.ComponentActivity
import com.techandthat.slpmealselection.data.ChildRecordsRepository
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.MealEntry
import com.techandthat.slpmealselection.model.TabletType
import com.techandthat.slpmealselection.network.ArborSyncService
import com.techandthat.slpmealselection.security.AuthManager
import com.techandthat.slpmealselection.ui.decodeAssetBitmap
import com.techandthat.slpmealselection.ui.hideSystemUi
import com.techandthat.slpmealselection.ui.setupKeyboardSafeLoginScroll
import com.techandthat.slpmealselection.ui.setupSchoolSpinner
import com.techandthat.slpmealselection.ui.showSplashThenSetup

// Coordinates setup, kitchen, and child tablet workflows for the app.
class MainActivity : ComponentActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal val repository = ChildRecordsRepository()
    internal val authManager = AuthManager()
    internal val arborSyncService = ArborSyncService()

    internal val schools = listOf(
        "St Luke's Primary",
        "St Mary's Primary",
        "St Peter's Primary"
    )
    // 50 students × 750ms/request ≈ 37.5s per batch — comfortably within the 90s callable timeout.
    internal val arborSyncBatchSize = 50

    internal val initialDummyData = listOf(
        MealEntry("Liam Smith", "Reception", "Tomato Pasta"),
        MealEntry("Emma Jones", "Reception", "Fish & Chips"),
        MealEntry("Noah Williams", "Reception", "Tomato Pasta"),
        MealEntry("Olivia Brown", "Year 1", "Jacket Potato"),
        MealEntry("George Taylor", "Year 1", "Fish & Chips"),
        MealEntry("Mia Davies", "Year 2", "Jacket Potato")
    )

    internal val fallbackMealByChild = initialDummyData.associate { "${it.name}|${it.clazz}" to it.meal }
    internal val simulatedDatabase = mutableListOf<MealEntry>()

    internal var selectedTabletType: TabletType? = null
    internal var selectedSchool: String = schools.first()
    internal var serviceStarted = false
    internal var mealTimeStarted = false
    internal var selectedClass: String? = null
    internal var childScreen: ChildScreen = ChildScreen.IDLE
    internal var activeOrder: MealEntry? = null
    internal var showWaitingOverlayAfterConfirm = false
    internal var firebaseStatusMessage: String? = null
    internal var isLoadingMeals = false
    internal var servicePausedByKitchen = false
    // Tracks Arbor student sync progress; 0 means total is not yet known (indeterminate).
    internal var syncProgressCurrent: Int = 0
    internal var syncProgressTotal: Int = 0

    // Inflates views, initializes setup UI, and wires primary event listeners.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi(window)
        setupSchoolSpinner(this, binding, schools)
        setupKeyboardSafeLoginScroll(binding)
        // Load SLP logo into splash and header areas (PNG with transparent background).
        decodeAssetBitmap(assets, "SLP.png")?.let {
            binding.splashLogo.setImageBitmap(it)
            binding.headerLogo.setImageBitmap(it)
        }
        // Load footer branding logo.
        decodeAssetBitmap(assets, "TechandThatLogoWhite.png")?.let {
            binding.footerLogo.setImageBitmap(it)
        }
        showSplashThenSetup(binding)
        bindListeners()
    }

    // Attaches all interactive click handlers used by setup/kitchen/child flows.
    internal fun bindListeners() {
        binding.loginButton.setOnClickListener { handleLogin() }

        binding.startServiceButton.setOnClickListener {
            serviceStarted = true
            servicePausedByKitchen = false
            renderAppContent()
        }

        binding.pauseServiceButton.setOnClickListener {
            servicePausedByKitchen = !servicePausedByKitchen
            renderAppContent()
        }

        binding.endServiceButton.setOnClickListener { confirmAndEndService() }
        binding.changeSchoolButton.setOnClickListener { showChangeSchoolDialog() }

        binding.loadTodaysMealsButton.setOnClickListener {
            if (isLoadingMeals || serviceStarted) return@setOnClickListener

            val hasPrepData = simulatedDatabase.any { !it.served }
            if (hasPrepData) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.reload_meals_title))
                    .setMessage(getString(R.string.reload_meals_message))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.load_todays_meals)) { _, _ ->
                        fetchStudentsFromArbor()
                    }
                    .show()
            } else {
                fetchStudentsFromArbor()
            }
        }

        binding.startMealTimeButton.setOnClickListener {
            mealTimeStarted = true
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }

        binding.backToClassesButton.setOnClickListener {
            selectedClass = null
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }

        binding.oopsButton.setOnClickListener {
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.NAME_SELECTION
            renderChildView()
        }

        binding.checkInSuccessButton.setOnClickListener {
            showWaitingOverlayAfterConfirm = true
            childScreen = ChildScreen.NAME_SELECTION
            renderChildView()
        }

        binding.mealServedButton.setOnClickListener {
            val servedEntry = activeOrder ?: return@setOnClickListener

            setMealServedLocally(servedEntry, served = true)
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = if (selectedClass.isNullOrBlank()) {
                ChildScreen.CLASS_SELECTION
            } else {
                ChildScreen.NAME_SELECTION
            }
            renderKitchenView()

            repository.markMealServed(
                entry = servedEntry,
                schoolName = selectedSchool,
                onSuccess = {
                    // No-op: UI already updated optimistically.
                },
                onFailure = { error ->
                    setMealServedLocally(servedEntry, served = false)
                    activeOrder = servedEntry
                    firebaseStatusMessage = "Failed to save served meal"
                    android.widget.Toast.makeText(
                        this,
                        error.message ?: "Failed to mark meal served",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    renderKitchenView()
                }
            )
        }

        binding.backToSetupButton.setOnClickListener { returnToSetup() }
    }

    // Delegates rendering to Kitchen or Child view based on selected tablet type.
    internal fun renderAppContent() {
        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView()
            TabletType.CHILD -> renderChildView()
            null -> Unit
        }
    }

    // Resolves selected tablet type radio option into TabletType enum.
    internal fun resolveTabletType(): TabletType? {
        val checkedId = binding.tabletTypeGroup.checkedRadioButtonId
        if (checkedId == View.NO_ID) return null
        val selectedButton = findViewById<RadioButton>(checkedId)
        return when (selectedButton.id) {
            binding.kitchenTabletRadio.id -> TabletType.KITCHEN
            binding.childTabletRadio.id -> TabletType.CHILD
            else -> null
        }
    }
}
