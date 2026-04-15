package com.techandthat.slpmealselection

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableResult
import java.util.Locale
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
    private val functions: FirebaseFunctions by lazy { FirebaseFunctions.getInstance("europe-west2") }

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
    private val simulatedDatabase = mutableListOf<MealEntry>()

    private var selectedTabletType: TabletType? = null
    private var selectedSchool: String = schools.first()
    private var serviceStarted = false
    private var mealTimeStarted = false
    private var selectedClass: String? = null
    private var childScreen: ChildScreen = ChildScreen.IDLE
    private var activeOrder: MealEntry? = null
    private var showWaitingOverlayAfterConfirm = false
    private var firebaseStatusMessage: String? = null
    private var isLoadingMeals = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi(window)
        setupSchoolSpinner(this, binding, schools)
        setupKeyboardSafeLoginScroll(binding)
        loadBrandAssets()
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
            if (isLoadingMeals || serviceStarted) return@setOnClickListener

            val hasPrepData = simulatedDatabase.any { !it.served }
            if (hasPrepData) {
                AlertDialog.Builder(this)
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
                isLoadingMeals = false
                firebaseStatusMessage = failureMessage
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                renderAppContent()
                renderKitchenView()
            }
    }

    private fun fetchStudentsFromArbor() {
        isLoadingMeals = true
        firebaseStatusMessage = null
        renderKitchenView()
        ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
    }

    private fun ensureAuthenticatedThenSync(retryOnUnauthenticated: Boolean) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            firebaseAuth.signInAnonymously()
                .addOnSuccessListener {
                    runArborSyncCallable(retryOnUnauthenticated)
                }
                .addOnFailureListener { error ->
                    val failureMessage = getString(
                        R.string.firebase_auth_failed_with_reason,
                        error.message ?: "unknown"
                    )
                    Log.e("ArborIntegration", "Anonymous auth failed before sync", error)
                    Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                }
            return
        }

        currentUser.getIdToken(true)
            .addOnSuccessListener {
                runArborSyncCallable(retryOnUnauthenticated)
            }
            .addOnFailureListener { error ->
                Log.e("ArborIntegration", "Token refresh failed before sync", error)
                firebaseAuth.signInAnonymously()
                    .addOnSuccessListener {
                        runArborSyncCallable(retryOnUnauthenticated)
                    }
                    .addOnFailureListener { authError ->
                        val failureMessage = getString(
                            R.string.firebase_auth_failed_with_reason,
                            authError.message ?: "unknown"
                        )
                        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                    }
            }
    }

    private fun runArborSyncCallable(retryOnUnauthenticated: Boolean) {
        functions
            .getHttpsCallable("getArborStudents")
            .call(
                mapOf(
                    "schoolName" to selectedSchool,
                    "maxRecords" to 10
                )
            )
            .addOnSuccessListener { result: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any>
                Log.d("ArborIntegration", "Success: $data")

                @Suppress("UNCHECKED_CAST")
                val students = data?.get("students") as? List<Map<String, Any>>
                if (!students.isNullOrEmpty()) {
                    val mapped = students.mapNotNull { student ->
                        val documentId = student["documentId"] as? String
                        val childName = student["childName"] as? String
                        val className = student["className"] as? String
                        val schoolName = student["schoolName"] as? String
                        val source = student["source"] as? String ?: "arbor"
                        val mealSelected = student["mealSelected"] as? String ?: "Not selected"
                        @Suppress("UNCHECKED_CAST")
                        val dietaryRequirements = (student["dietaryRequirements"] as? List<Any>)
                            ?.mapNotNull { it as? String }
                            ?: emptyList()
                        if (documentId.isNullOrBlank() || childName.isNullOrBlank() || className.isNullOrBlank() || schoolName.isNullOrBlank()) {
                            null
                        } else {
                            ChildRecordsRepository.ArborStudentRecord(
                                documentId = documentId,
                                childName = childName,
                                className = className,
                                schoolName = schoolName,
                                source = source,
                                mealSelected = mealSelected,
                                dietaryRequirements = dietaryRequirements
                            )
                        }
                    }

                    repository.upsertArborRecords(
                        records = mapped,
                        onSuccess = {
                            loadChildRecordsFromFirestore()
                            isLoadingMeals = false
                            val rateLimited = data.get("rateLimited") as? Boolean ?: false
                            val message = data.get("message") as? String
                            val toastText = if (rateLimited) {
                                message ?: "Arbor rate limited. Please retry in a minute."
                            } else {
                                message ?: "Arbor students synced to Firebase"
                            }
                            firebaseStatusMessage = null
                            renderKitchenView()
                            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Log.e("ArborIntegration", "Client-side Firestore upsert failed", error)
                            isLoadingMeals = false
                            firebaseStatusMessage = "Failed to save meals locally"
                            loadChildRecordsFromFirestore()
                            renderKitchenView()
                        }
                    )
                } else {
                    isLoadingMeals = false
                    val rateLimited = data?.get("rateLimited") as? Boolean ?: false
                    val message = data?.get("message") as? String
                    firebaseStatusMessage = if (rateLimited) {
                        message ?: "Arbor rate limited. Please retry in a minute."
                    } else {
                        null
                    }
                    loadChildRecordsFromFirestore()
                    renderKitchenView()
                    Toast.makeText(this, message ?: "No meals returned", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception: Exception ->
                val functionsException = exception as? FirebaseFunctionsException
                val isUnauthenticated = functionsException?.code == FirebaseFunctionsException.Code.UNAUTHENTICATED
                if (retryOnUnauthenticated && isUnauthenticated) {
                    Log.w("ArborIntegration", "Callable returned UNAUTHENTICATED, retrying with fresh auth")
                    firebaseAuth.signOut()
                    ensureAuthenticatedThenSync(retryOnUnauthenticated = false)
                    return@addOnFailureListener
                }

                isLoadingMeals = false
                firebaseStatusMessage = "Failed to sync meals"
                renderKitchenView()
                Log.e("ArborIntegration", "Failed to fetch students", exception)
                Toast.makeText(this, "Failed to sync students from Arbor", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadChildRecordsFromFirestore() {
        repository.loadRecords(
            onSuccess = { records ->
                if (records.isEmpty()) {
                    simulatedDatabase.clear()
                    renderAppContent()
                    return@loadRecords
                }

                val mapped = records.map { record ->
                    val meal = record.mealSelected
                        ?.takeIf { it.isNotBlank() }
                        ?: fallbackMealByChild["${record.childName}|${record.className}"]
                        ?: "Meal not selected"
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
        authenticateThenLoadRoster()
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
        TabletType.KITCHEN -> username == "k" && password == "k"
        TabletType.CHILD -> username == "c" && password == "c"
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
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
        binding.headerTitle.text = getString(R.string.kitchen_side)
        binding.headerSubtitle.text = getString(R.string.school_selected, selectedSchool)
        binding.contentTitle.text = getString(R.string.kitchen_dashboard_title)
        binding.contentSubtitle.visibility = View.GONE

        binding.kitchenContent.visibility = View.VISIBLE
        binding.childContent.visibility = View.GONE

        val hasCheckInStarted = activeOrder != null || simulatedDatabase.any { it.served }
        val shouldHideLoadAndStart = serviceStarted
        val outstandingMeals = simulatedDatabase.filterNot { it.served }
        val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
        val hasPrepData = outstandingMeals.isNotEmpty()

        binding.serviceStatusText.text = if (serviceStarted) {
            getString(R.string.service_status_started)
        } else if (!hasPrepData) {
            getString(R.string.service_status_ready_to_load)
        } else {
            ""
        }
        binding.prepListTitle.text = if (serviceStarted) {
            getString(R.string.meals_to_be_served)
        } else {
            getString(R.string.meals_to_prepare)
        }
        binding.serviceStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (serviceStarted) R.color.kitchen_success else R.color.kitchen_text_secondary
            )
        )

        binding.startServiceButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
        binding.loadTodaysMealsButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE

        binding.startServiceButton.isEnabled = !serviceStarted && hasPrepData
        binding.startServiceButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (binding.startServiceButton.isEnabled) R.color.kitchen_success else R.color.meal_count_text
            )
        )
        binding.startServiceButton.alpha = if (binding.startServiceButton.isEnabled) 1f else 0.7f

        val canLoadMeals = !serviceStarted && !isLoadingMeals
        binding.loadTodaysMealsButton.isEnabled = canLoadMeals
        binding.loadTodaysMealsButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (!hasPrepData && canLoadMeals) R.color.kitchen_success else R.color.kitchen_accent
            )
        )
        binding.loadTodaysMealsButton.alpha = if (canLoadMeals) 1f else 0.8f
        binding.loadTodaysMealsButton.text = getString(R.string.load_todays_meals)

        binding.prepLoadingText.visibility = if (isLoadingMeals) View.VISIBLE else View.GONE

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

        renderMealPrepRows(volumes)

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
            binding.kitchenContent.removeView(binding.prepAndLoadRow)
            binding.kitchenContent.addView(binding.prepAndLoadRow)
        } else {
            binding.kitchenContent.removeView(binding.prepAndLoadRow)
            binding.kitchenContent.addView(binding.prepAndLoadRow, 0)
            binding.kitchenContent.removeView(binding.kitchenOrderContainer)
            binding.kitchenContent.addView(binding.kitchenOrderContainer)
        }

        binding.appScrollView.scrollTo(0, 0)
    }

    private fun renderMealPrepRows(volumes: Map<String, Int>) {
        binding.prepSummaryContainer.removeAllViews()

        if (volumes.isEmpty()) {
            val emptyState = TextView(this).apply {
                text = getString(R.string.no_meals_loaded)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.meal_count_text))
                textSize = 20f
            }
            binding.prepSummaryContainer.addView(emptyState)
            return
        }

        val inflater = LayoutInflater.from(this)
        volumes.entries
            .sortedBy { it.key.lowercase(Locale.getDefault()) }
            .forEach { (mealName, count) ->
                val row = inflater.inflate(R.layout.item_meal_prep, binding.prepSummaryContainer, false)
                val iconView = row.findViewById<ImageView>(R.id.mealTypeIcon)
                val countView = row.findViewById<TextView>(R.id.mealCountText)
                val nameView = row.findViewById<TextView>(R.id.mealNameText)

                iconView.setImageResource(mealIconFor(mealName))
                countView.text = getString(R.string.meal_count_format, count)
                nameView.text = mealName
                binding.prepSummaryContainer.addView(row)
            }
    }

    private fun mealIconFor(mealName: String): Int {
        val normalized = mealName.lowercase(Locale.getDefault())
        return when {
            "wrap" in normalized -> android.R.drawable.ic_menu_upload
            "fish" in normalized -> android.R.drawable.ic_menu_gallery
            "potato" in normalized -> android.R.drawable.ic_menu_my_calendar
            "pasta" in normalized -> android.R.drawable.ic_menu_sort_by_size
            "curry" in normalized -> android.R.drawable.ic_menu_compass
            else -> android.R.drawable.ic_menu_info_details
        }
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
