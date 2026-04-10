package com.techandthat.slpmealselection

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.techandthat.slpmealselection.data.ChildRecordsRepository
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.MealEntry
import com.techandthat.slpmealselection.model.TabletType
import com.techandthat.slpmealselection.ui.decodeAssetBitmap
import com.techandthat.slpmealselection.ui.hideSystemUi
import com.techandthat.slpmealselection.ui.renderClassSelectionStep
import com.techandthat.slpmealselection.ui.renderNameSelectionStep
import com.techandthat.slpmealselection.ui.renderSuccessStep
import com.techandthat.slpmealselection.ui.setupKeyboardSafeLoginScroll
import com.techandthat.slpmealselection.ui.setupSchoolSpinner
import com.techandthat.slpmealselection.ui.showSplashThenSetup

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository = ChildRecordsRepository()
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val schools = listOf(
        "St Luke's Primary",
        "St Mary's Primary",
        "St Peter's Primary"
    )

    private val initialDummyData = listOf(
        MealEntry("Liam Smith", "Reception", "Tomato Pasta"),
        MealEntry("Emma Jones", "Reception", "Fish & Chips"),
        MealEntry("Noah Williams", "Reception", "Tomato Pasta"),
        MealEntry("Olivia Brown", "Year 1", "Jacket Potato"),
        MealEntry("George Taylor", "Year 1", "Fish & Chips"),
        MealEntry("Mia Davies", "Year 2", "Jacket Potato")
    )

    private val fallbackMealByChild = initialDummyData.associate { "${it.name}|${it.clazz}" to it.meal }
    private val simulatedDatabase = initialDummyData.toMutableList()

    private var selectedTabletType: TabletType? = null
    private var selectedSchool: String = schools.first()
    private var serviceStarted = false
    private var mealTimeStarted = false
    private var selectedClass: String? = null
    private var childScreen: ChildScreen = ChildScreen.IDLE
    private var activeOrder: MealEntry? = null
    private var showWaitingOverlayAfterConfirm = false
    private var firebaseStatusMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi(window)
        setupSchoolSpinner(this, binding, schools)
        setupKeyboardSafeLoginScroll(binding)
        loadBrandAssets()
        authenticateThenLoadRoster()
        showSplashThenSetup(binding)
        bindListeners()
    }

    private fun bindListeners() {
        binding.loginButton.setOnClickListener { handleLogin() }
        binding.startServiceButton.setOnClickListener {
            serviceStarted = true
            renderAppContent()
        }
        binding.endServiceButton.setOnClickListener { confirmAndEndService() }
        binding.changeSchoolButton.setOnClickListener { showChangeSchoolDialog() }
        binding.loadTodaysMealsButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.load_todays_meals_placeholder), Toast.LENGTH_SHORT).show()
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
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }
        binding.mealServedButton.setOnClickListener {
            activeOrder?.served = true
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            selectedClass = null
            childScreen = ChildScreen.CLASS_SELECTION
            renderAppContent()
        }
        binding.backToSetupButton.setOnClickListener { returnToSetup() }
    }

    private fun loadBrandAssets() {
        decodeAssetBitmap(assets, "SLP.jpg")?.let {
            binding.splashLogo.setImageBitmap(it)
            binding.headerLogo.setImageBitmap(it)
        }

        decodeAssetBitmap(assets, "TechandThatLogoWhite.png")?.let {
            binding.footerLogo.setImageBitmap(it)
        }
    }

    private fun authenticateThenLoadRoster() {
        if (firebaseAuth.currentUser != null) {
            loadChildRecordsFromFirestore()
            return
        }

        firebaseAuth.signInAnonymously()
            .addOnSuccessListener {
                loadChildRecordsFromFirestore()
            }
            .addOnFailureListener { error ->
                val failureMessage = getString(
                    R.string.firebase_auth_failed_with_reason,
                    error.message ?: "unknown"
                )
                firebaseStatusMessage = failureMessage
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                renderAppContent()
                renderKitchenView()
            }
    }

    private fun loadChildRecordsFromFirestore() {
        repository.loadRecords(
            onSuccess = { records ->
                if (records.isEmpty()) {
                    seedFirestoreFromInitialData()
                    return@loadRecords
                }

                val mapped = records.map { record ->
                    val meal = fallbackMealByChild["${record.childName}|${record.className}"] ?: "Meal"
                    MealEntry(record.childName, record.className, meal)
                }

                simulatedDatabase.clear()
                simulatedDatabase.addAll(mapped)
                renderAppContent()
            },
            onFailure = { error ->
                val failureMessage = getString(R.string.firebase_load_failed_with_reason, error.message ?: "unknown")
                firebaseStatusMessage = failureMessage
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                renderAppContent()
                renderKitchenView()
            }
        )
    }

    private fun seedFirestoreFromInitialData() {
        repository.seedInitialData(
            initialDummyData = initialDummyData,
            schoolName = selectedSchool,
            onSuccess = {
                loadChildRecordsFromFirestore()
                renderKitchenView()
            },
            onFailure = { error ->
                val failureMessage = getString(
                    R.string.firebase_seed_failed_with_reason,
                    error.message ?: "unknown"
                )
                firebaseStatusMessage = failureMessage
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                renderKitchenView()
            }
        )
    }

    private fun showChangeSchoolDialog() {
        val selectedIndex = schools.indexOf(selectedSchool).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_school))
            .setSingleChoiceItems(schools.toTypedArray(), selectedIndex) { dialog, which ->
                selectedSchool = schools[which]
                renderAppContent()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmAndEndService() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_end_service_title))
            .setMessage(getString(R.string.confirm_end_service_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.end_service)) { _, _ ->
                endServiceAndDeleteRecords()
            }
            .show()
    }

    private fun endServiceAndDeleteRecords() {
        repository.deleteAllRecords(
            onSuccess = {
                simulatedDatabase.clear()
                activeOrder = null
                selectedClass = null
                showWaitingOverlayAfterConfirm = false
                serviceStarted = false
                mealTimeStarted = false
                childScreen = ChildScreen.IDLE
                renderAppContent()
                Toast.makeText(this, getString(R.string.service_reset_done), Toast.LENGTH_SHORT).show()
            },
            onFailure = { error ->
                val failureMessage = getString(R.string.firebase_delete_failed_with_reason, error.message ?: "unknown")
                firebaseStatusMessage = failureMessage
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                renderKitchenView()
            }
        )
    }

    private fun handleLogin() {
        val tabletType = resolveTabletType() ?: run {
            showSetupError(getString(R.string.select_tablet_type_error))
            return
        }

        val username = binding.usernameInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim()?.lowercase().orEmpty()

        if (!isCredentialValid(tabletType, username, password)) {
            showSetupError(getString(R.string.invalid_credentials_error))
            return
        }

        selectedTabletType = tabletType
        selectedSchool = binding.initialSchoolSpinner.selectedItem?.toString().orEmpty().ifBlank { schools.first() }
        binding.setupErrorText.visibility = View.GONE
        binding.setupContainer.visibility = View.GONE
        binding.appContainer.visibility = View.VISIBLE
        renderAppContent()
    }

    private fun resolveTabletType(): TabletType? {
        val checkedId = binding.tabletTypeGroup.checkedRadioButtonId
        if (checkedId == View.NO_ID) return null

        val selectedButton = findViewById<RadioButton>(checkedId)
        return when (selectedButton.id) {
            binding.kitchenTabletRadio.id -> TabletType.KITCHEN
            binding.childTabletRadio.id -> TabletType.CHILD
            else -> null
        }
    }

    private fun isCredentialValid(tabletType: TabletType, username: String, password: String): Boolean = when (tabletType) {
        TabletType.KITCHEN -> username == "kitchen" && password == "kitchen"
        TabletType.CHILD -> username == "child" && password == "child"
    }

    private fun showSetupError(message: String) {
        binding.setupErrorText.text = message
        binding.setupErrorText.visibility = View.VISIBLE
    }

    private fun returnToSetup() {
        binding.appContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE

        selectedTabletType = null
        binding.tabletTypeGroup.clearCheck()
        binding.usernameInput.text?.clear()
        binding.passwordInput.text?.clear()
        binding.setupErrorText.visibility = View.GONE

        if (activeOrder?.served == true) {
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            selectedClass = null
            mealTimeStarted = false
            childScreen = ChildScreen.IDLE
        }
    }

    private fun renderAppContent() {
        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView()
            TabletType.CHILD -> renderChildView()
            null -> Unit
        }
    }

    private fun renderKitchenView() {
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.slp_blue))
        binding.headerTitle.text = getString(R.string.kitchen_side)
        binding.headerSubtitle.text = getString(R.string.school_selected, selectedSchool)
        binding.contentTitle.text = getString(R.string.kitchen_dashboard_title)
        binding.contentSubtitle.visibility = View.GONE

        binding.kitchenContent.visibility = View.VISIBLE
        binding.childContent.visibility = View.GONE

        binding.serviceStatusText.text = if (serviceStarted) {
            getString(R.string.service_status_started)
        } else {
            getString(R.string.service_status_not_started)
        }
        binding.prepListTitle.text = if (serviceStarted) {
            getString(R.string.meals_to_be_served)
        } else {
            getString(R.string.meals_to_prepare)
        }
        binding.serviceStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (serviceStarted) R.color.child_green else R.color.slp_blue
            )
        )

        val hasCheckInStarted = activeOrder != null || simulatedDatabase.any { it.served }
        val shouldHideLoadAndStart = serviceStarted && hasCheckInStarted

        binding.startServiceButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
        binding.loadTodaysMealsButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
        binding.startServiceButton.isEnabled = !serviceStarted
        binding.loadTodaysMealsButton.isEnabled = !serviceStarted
        binding.changeSchoolButton.visibility = View.VISIBLE
        binding.endServiceButton.visibility = View.VISIBLE
        binding.changeSchoolButton.isEnabled = !serviceStarted
        binding.endServiceButton.isEnabled = serviceStarted || simulatedDatabase.isNotEmpty() || activeOrder != null

        if (firebaseStatusMessage.isNullOrBlank()) {
            binding.firebaseStatusText.visibility = View.GONE
        } else {
            binding.firebaseStatusText.text = firebaseStatusMessage
            binding.firebaseStatusText.visibility = View.VISIBLE
        }

        val outstandingMeals = simulatedDatabase.filterNot { it.served }
        val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
        binding.prepSummary.text = if (volumes.isEmpty()) {
            getString(R.string.all_meals_served)
        } else {
            volumes.entries.joinToString(separator = "\n") { (meal, count) -> "• $meal: $count" }
        }

        if (serviceStarted && activeOrder != null) {
            binding.kitchenOrderContainer.visibility = View.VISIBLE
            binding.kitchenOrderChildName.text = activeOrder?.name
            binding.kitchenOrderMealName.text = activeOrder?.meal
            binding.mealServedButton.isEnabled = true
        } else {
            binding.kitchenOrderContainer.visibility = View.GONE
        }

        if (hasCheckInStarted) {
            binding.kitchenContent.removeView(binding.kitchenOrderContainer)
            binding.kitchenContent.addView(binding.kitchenOrderContainer, 0)
            binding.kitchenContent.removeView(binding.prepListContainer)
            binding.kitchenContent.addView(binding.prepListContainer)
        } else {
            binding.kitchenContent.removeView(binding.prepListContainer)
            binding.kitchenContent.addView(binding.prepListContainer, 0)
            binding.kitchenContent.removeView(binding.kitchenOrderContainer)
            binding.kitchenContent.addView(binding.kitchenOrderContainer)
        }

        binding.appScrollView.scrollTo(0, 0)
    }

    private fun renderChildView() {
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.child_orange))
        binding.headerTitle.text = getString(R.string.child_facing)
        binding.headerSubtitle.text = getString(R.string.child_tablet_mode)
        binding.loadTodaysMealsButton.visibility = View.GONE
        binding.changeSchoolButton.visibility = View.GONE
        binding.endServiceButton.visibility = View.GONE
        binding.contentTitle.text = getString(R.string.child_dashboard_title)
        binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle_simple)

        binding.kitchenContent.visibility = View.GONE
        binding.childContent.visibility = View.VISIBLE
        binding.contentTitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

        if (!serviceStarted) {
            mealTimeStarted = false
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.IDLE
            binding.childServiceGateText.text = getString(R.string.child_waiting_for_service)
            binding.childServiceGateText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.contentSubtitle.visibility = View.GONE
            binding.startMealTimeButton.visibility = View.GONE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        binding.childServiceGateText.text = getString(R.string.child_service_live)
        binding.childServiceGateText.setTextColor(ContextCompat.getColor(this, R.color.child_green))
        binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.childServiceGateText.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.startMealTimeButton.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

        if (!mealTimeStarted) {
            childScreen = ChildScreen.IDLE
            binding.contentTitle.visibility = View.VISIBLE
            binding.contentSubtitle.visibility = View.VISIBLE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        binding.childStepContainer.visibility = View.VISIBLE

        when (childScreen) {
            ChildScreen.IDLE -> {
                childScreen = ChildScreen.CLASS_SELECTION
                renderClassSelectionStep(
                    context = this,
                    binding = binding,
                    simulatedDatabase = simulatedDatabase,
                    showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                    onClassSelected = { selected ->
                        selectedClass = selected
                        childScreen = ChildScreen.NAME_SELECTION
                        renderChildView()
                    }
                )
            }

            ChildScreen.CLASS_SELECTION -> {
                renderClassSelectionStep(
                    context = this,
                    binding = binding,
                    simulatedDatabase = simulatedDatabase,
                    showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                    onClassSelected = { selected ->
                        selectedClass = selected
                        childScreen = ChildScreen.NAME_SELECTION
                        renderChildView()
                    }
                )
            }

            ChildScreen.NAME_SELECTION -> {
                renderNameSelectionStep(
                    context = this,
                    binding = binding,
                    simulatedDatabase = simulatedDatabase,
                    selectedClass = selectedClass,
                    activeOrder = activeOrder,
                    showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                    onMissingClass = {
                        childScreen = ChildScreen.CLASS_SELECTION
                        renderChildView()
                    },
                    onChildSelected = { child ->
                        activeOrder = child
                        childScreen = ChildScreen.SUCCESS
                        renderAppContent()
                    }
                )
            }

            ChildScreen.SUCCESS -> renderSuccessStep(binding)
        }

        val shouldShowOverlay = showWaitingOverlayAfterConfirm && activeOrder != null && childScreen == ChildScreen.CLASS_SELECTION
        binding.waitingOverlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
    }
}
