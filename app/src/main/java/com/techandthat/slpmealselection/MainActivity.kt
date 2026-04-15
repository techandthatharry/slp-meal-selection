package com.techandthat.slpmealselection

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.firebase.functions.FirebaseFunctionsException
import com.techandthat.slpmealselection.data.ChildRecordsRepository
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.MealEntry
import com.techandthat.slpmealselection.model.TabletType
import com.techandthat.slpmealselection.network.ArborPayloadMapper
import com.techandthat.slpmealselection.network.ArborSyncService
import com.techandthat.slpmealselection.security.AuthManager
import com.techandthat.slpmealselection.ui.MealPrepUi
import com.techandthat.slpmealselection.ui.decodeAssetBitmap
import com.techandthat.slpmealselection.ui.hideSystemUi
import com.techandthat.slpmealselection.ui.renderClassSelectionStep
import com.techandthat.slpmealselection.ui.renderNameSelectionStep
import com.techandthat.slpmealselection.ui.renderSuccessStep
import com.techandthat.slpmealselection.ui.setupKeyboardSafeLoginScroll
import com.techandthat.slpmealselection.ui.setupSchoolSpinner
import com.techandthat.slpmealselection.ui.showSplashThenSetup

// Coordinates setup, kitchen, and child tablet workflows for the app.
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository = ChildRecordsRepository()
    private val authManager = AuthManager()
    private val arborSyncService = ArborSyncService()

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
    private var servicePausedByKitchen = false

    // Inflates views, initializes setup UI, and wires primary event listeners.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply initial shell configuration for kiosk-like tablet behavior.
        hideSystemUi(window)
        setupSchoolSpinner(this, binding, schools)
        setupKeyboardSafeLoginScroll(binding)
        loadBrandAssets()
        showSplashThenSetup(binding)
        bindListeners()
    }

    // Attaches all interactive click handlers used by setup/kitchen/child flows.
    private fun bindListeners() {
        // Login transitions from setup to app mode after credential validation.
        binding.loginButton.setOnClickListener { handleLogin() }

        // Start Service enables service-time behavior and UI state.
        binding.startServiceButton.setOnClickListener {
            serviceStarted = true
            servicePausedByKitchen = false
            renderAppContent()
        }

        // Pause/Resume Service lets kitchen temporarily stop child check-ins.
        binding.pauseServiceButton.setOnClickListener {
            servicePausedByKitchen = !servicePausedByKitchen
            renderAppContent()
        }

        // End Service performs reset and Firestore cleanup confirmation flow.
        binding.endServiceButton.setOnClickListener { confirmAndEndService() }

        // Change school opens chooser dialog for active school context.
        binding.changeSchoolButton.setOnClickListener { showChangeSchoolDialog() }

        // Load meals triggers Arbor sync and optional reload confirmation.
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

        // Child flow: start meal-time moves child tablet into class selection.
        binding.startMealTimeButton.setOnClickListener {
            mealTimeStarted = true
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }

        // Child flow: explicitly switch to a different class from name list.
        binding.backToClassesButton.setOnClickListener {
            selectedClass = null
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }

        // Child flow: "Oops" returns from success to name selection.
        binding.oopsButton.setOnClickListener {
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.NAME_SELECTION
            renderChildView()
        }

        // Child flow: continue from success to waiting/name state for same class.
        binding.checkInSuccessButton.setOnClickListener {
            showWaitingOverlayAfterConfirm = true
            childScreen = ChildScreen.NAME_SELECTION
            renderChildView()
        }

        // Kitchen flow: mark active meal served and return to class-selection route.
        binding.mealServedButton.setOnClickListener {
            val servedEntry = activeOrder ?: return@setOnClickListener

            // Optimistically update UI so meal counts decrement immediately.
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
                    // Roll back optimistic state if persistence fails.
                    setMealServedLocally(servedEntry, served = false)
                    activeOrder = servedEntry
                    firebaseStatusMessage = "Failed to save served meal"
                    Toast.makeText(this, error.message ?: "Failed to mark meal served", Toast.LENGTH_LONG).show()
                    renderKitchenView()
                }
            )
        }

        // Global: return to setup screen and clear current session UI state.
        binding.backToSetupButton.setOnClickListener { returnToSetup() }
    }

    // Loads branding assets (SLP and TechAndThat logos) into the UI.
    private fun loadBrandAssets() {
        // Load SLP logo into splash and header areas.
        decodeAssetBitmap(assets, "SLP.jpg")?.let {
            binding.splashLogo.setImageBitmap(it)
            binding.headerLogo.setImageBitmap(it)
        }

        // Load footer branding logo.
        decodeAssetBitmap(assets, "TechandThatLogoWhite.png")?.let {
            binding.footerLogo.setImageBitmap(it)
        }
    }

    // Ensures user is authenticated before reading roster data from Firestore.
    private fun authenticateThenLoadRoster() {
        // Reuse current user session when available.
        if (authManager.currentUserExists()) {
            loadChildRecordsFromFirestore()
            return
        }

        // Perform anonymous sign-in and then fetch roster.
        authManager.signInAnonymously(
            onSuccess = {
                loadChildRecordsFromFirestore()
            },
            onFailure = { error ->
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
        )
    }

    // Starts Arbor sync flow and updates loading state/UI before network call.
    private fun fetchStudentsFromArbor() {
        isLoadingMeals = true
        firebaseStatusMessage = null
        renderKitchenView()
        ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
    }

    // Verifies fresh auth token exists prior to invoking callable sync.
    private fun ensureAuthenticatedThenSync(retryOnUnauthenticated: Boolean) {
        authManager.ensureFreshAuth(
            onReady = {
                runArborSyncCallable(retryOnUnauthenticated)
            },
            onFailure = { error ->
                val failureMessage = getString(
                    R.string.firebase_auth_failed_with_reason,
                    error.message ?: "unknown"
                )
                Log.e("ArborIntegration", "Auth failed before sync", error)
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
            }
        )
    }

    // Calls getArborStudents and persists returned records into Firestore.
    private fun runArborSyncCallable(retryOnUnauthenticated: Boolean) {
        arborSyncService.syncStudents(
            schoolName = selectedSchool,
            maxRecords = 10,
            onSuccess = { data ->
                Log.d("ArborIntegration", "Success: $data")

                // Convert payload into validated records for repository upsert.
                val mapped = ArborPayloadMapper.mapStudentRecords(data)
                if (mapped.isNotEmpty()) {
                    repository.upsertArborRecords(
                        records = mapped,
                        onSuccess = {
                            // Refresh local UI data after successful write.
                            loadChildRecordsFromFirestore()
                            isLoadingMeals = false
                            val rateLimited = data?.get("rateLimited") as? Boolean ?: false
                            val message = data?.get("message") as? String
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
                            // Surface local write failures and still refresh UI state.
                            Log.e("ArborIntegration", "Client-side Firestore upsert failed", error)
                            isLoadingMeals = false
                            firebaseStatusMessage = "Failed to save meals locally"
                            loadChildRecordsFromFirestore()
                            renderKitchenView()
                        }
                    )
                } else {
                    // Handle empty payloads and optional rate-limit response messaging.
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
            },
            onFailure = { exception ->
                val functionsException = exception as? FirebaseFunctionsException
                val isUnauthenticated = functionsException?.code == FirebaseFunctionsException.Code.UNAUTHENTICATED

                // Retry once with forced re-auth when callable returns UNAUTHENTICATED.
                if (retryOnUnauthenticated && isUnauthenticated) {
                    Log.w("ArborIntegration", "Callable returned UNAUTHENTICATED, retrying with fresh auth")
                    authManager.signOut()
                    ensureAuthenticatedThenSync(retryOnUnauthenticated = false)
                    return@syncStudents
                }

                // Surface final sync failure to kitchen status area and toast.
                isLoadingMeals = false
                firebaseStatusMessage = "Failed to sync meals"
                renderKitchenView()
                Log.e("ArborIntegration", "Failed to fetch students", exception)
                Toast.makeText(this, "Failed to sync students from Arbor", Toast.LENGTH_LONG).show()
            }
        )
    }

    // Updates in-memory meal entries by document ID/name+class for immediate UI refreshes.
    private fun setMealServedLocally(target: MealEntry, served: Boolean) {
        val index = simulatedDatabase.indexOfFirst { entry ->
            if (!target.documentId.isNullOrBlank() && !entry.documentId.isNullOrBlank()) {
                entry.documentId == target.documentId
            } else {
                entry.name == target.name && entry.clazz == target.clazz && entry.meal == target.meal
            }
        }

        if (index >= 0) {
            simulatedDatabase[index].served = served
        } else {
            target.served = served
        }
    }

    // Loads Firestore child records into in-memory meal list used by both tablet modes.
    private fun loadChildRecordsFromFirestore() {
        repository.loadRecords(
            onSuccess = { records ->
                // Clear UI data when no rows exist in Firestore.
                if (records.isEmpty()) {
                    simulatedDatabase.clear()
                    renderAppContent()
                    return@loadRecords
                }

                // Map records to MealEntry, with fallback meal labels when needed.
                val mapped = records.map { record ->
                    val meal = record.mealSelected
                        ?.takeIf { it.isNotBlank() }
                        ?: fallbackMealByChild["${record.childName}|${record.className}"]
                        ?: "Meal not selected"
                    MealEntry(
                        name = record.childName,
                        clazz = record.className,
                        meal = meal,
                        documentId = record.documentId,
                        schoolName = record.schoolName,
                        dietaryRequirements = record.dietaryRequirements,
                        served = record.served
                    )
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

    // Shows school picker dialog and updates selected school when user chooses one.
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

    // Opens confirmation dialog before ending service and deleting records.
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

    // Resets session state and clears Firestore child records at end of service.
    private fun endServiceAndDeleteRecords() {
        repository.deleteAllRecords(
            onSuccess = {
                simulatedDatabase.clear()
                activeOrder = null
                selectedClass = null
                showWaitingOverlayAfterConfirm = false
                serviceStarted = false
                servicePausedByKitchen = false
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

    // Validates login inputs and enters the selected tablet mode.
    private fun handleLogin() {
        val tabletType = resolveTabletType() ?: run {
            showSetupError(getString(R.string.select_tablet_type_error))
            return
        }

        val username = binding.usernameInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim()?.lowercase().orEmpty()

        // Reject invalid credentials before switching into app container.
        if (!isCredentialValid(tabletType, username, password)) {
            showSetupError(getString(R.string.invalid_credentials_error))
            return
        }

        selectedTabletType = tabletType
        selectedSchool = binding.initialSchoolSpinner.selectedItem?.toString().orEmpty().ifBlank { schools.first() }
        binding.setupErrorText.visibility = View.GONE
        binding.setupContainer.visibility = View.GONE
        binding.appContainer.visibility = View.VISIBLE

        // Authenticate and immediately render mode-specific dashboard.
        authenticateThenLoadRoster()
        renderAppContent()
    }

    // Resolves selected tablet type radio option into TabletType enum.
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

    // Verifies static kiosk credentials for the selected tablet mode.
    private fun isCredentialValid(tabletType: TabletType, username: String, password: String): Boolean = when (tabletType) {
        TabletType.KITCHEN -> username == "k" && password == "k"
        TabletType.CHILD -> username == "c" && password == "c"
    }

    // Displays setup validation error text under login controls.
    private fun showSetupError(message: String) {
        binding.setupErrorText.text = message
        binding.setupErrorText.visibility = View.VISIBLE
    }

    // Returns app to setup screen and clears mode-specific transient state.
    private fun returnToSetup() {
        binding.appContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE

        // Reset setup form fields and selected mode.
        selectedTabletType = null
        binding.tabletTypeGroup.clearCheck()
        binding.usernameInput.text?.clear()
        binding.passwordInput.text?.clear()
        binding.setupErrorText.visibility = View.GONE

        // Reset child flow if last checked-in order was completed.
        if (activeOrder?.served == true) {
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            selectedClass = null
            mealTimeStarted = false
            childScreen = ChildScreen.IDLE
        }
    }

    // Delegates rendering to Kitchen or Child view based on selected tablet type.
    private fun renderAppContent() {
        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView()
            TabletType.CHILD -> renderChildView()
            null -> Unit
        }
    }

    // Renders kitchen dashboard state, button availability, and prep summary.
    private fun renderKitchenView() {
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
        binding.headerTitle.text = getString(R.string.kitchen_side)
        binding.headerSubtitle.text = getString(R.string.school_selected, selectedSchool)
        binding.contentTitle.text = getString(R.string.kitchen_dashboard_title)
        binding.contentSubtitle.visibility = View.GONE

        binding.kitchenContent.visibility = View.VISIBLE
        binding.childContent.visibility = View.GONE

        // Compute derived kitchen state for rendering.
        val hasCheckInStarted = activeOrder != null || simulatedDatabase.any { it.served }
        val shouldHideLoadAndStart = serviceStarted
        val outstandingMeals = simulatedDatabase.filterNot { it.served }
        val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
        val hasPrepData = outstandingMeals.isNotEmpty()

        // Update service status messaging and prep card title.
        binding.serviceStatusText.text = if (serviceStarted && servicePausedByKitchen) {
            getString(R.string.service_status_paused)
        } else if (serviceStarted) {
            getString(R.string.service_status_started)
        } else if (!hasPrepData) {
            getString(R.string.service_status_ready_to_load)
        } else {
            getString(R.string.service_status_ready_to_start)
        }
        binding.prepListTitle.text = if (serviceStarted) {
            getString(R.string.meals_to_be_served)
        } else {
            getString(R.string.meals_to_prepare)
        }
        binding.serviceStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    serviceStarted && servicePausedByKitchen -> R.color.kitchen_danger
                    serviceStarted -> R.color.kitchen_success
                    else -> R.color.kitchen_text_secondary
                }
            )
        )

        // Toggle action button visibility when service begins.
        binding.startServiceButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
        binding.loadTodaysMealsButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
        binding.pauseServiceButton.visibility = if (serviceStarted) View.VISIBLE else View.GONE

        // Configure Start Service button enablement and tint.
        binding.startServiceButton.isEnabled = !serviceStarted && hasPrepData
        binding.startServiceButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (binding.startServiceButton.isEnabled) R.color.kitchen_success else R.color.meal_count_text
            )
        )
        binding.startServiceButton.alpha = if (binding.startServiceButton.isEnabled) 1f else 0.7f

        // Configure Pause Service button label/tint while service is active.
        binding.pauseServiceButton.text = if (servicePausedByKitchen) {
            getString(R.string.resume_service_button_with_icon)
        } else {
            getString(R.string.pause_service_button_with_icon)
        }
        binding.pauseServiceButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.kitchen_accent)
        )

        // Configure Load Meals button enablement and tint.
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

        // Show inline prep loading indicator and status bar while sync is active.
        binding.prepLoadingText.visibility = if (isLoadingMeals) View.VISIBLE else View.GONE
        binding.prepLoadingProgress.visibility = if (isLoadingMeals) View.VISIBLE else View.GONE

        // Keep school/end controls visible and set enabled state.
        binding.changeSchoolButton.visibility = View.VISIBLE
        binding.endServiceButton.visibility = View.VISIBLE
        binding.changeSchoolButton.isEnabled = !serviceStarted
        binding.endServiceButton.isEnabled = serviceStarted || simulatedDatabase.isNotEmpty() || activeOrder != null

        // Show firebase status only when a message exists.
        if (firebaseStatusMessage.isNullOrBlank()) {
            binding.firebaseStatusText.visibility = View.GONE
        } else {
            binding.firebaseStatusText.text = firebaseStatusMessage
            binding.firebaseStatusText.visibility = View.VISIBLE
        }

        // Render grouped meal-prep rows using shared UI helper.
        MealPrepUi.renderMealPrepRows(this, binding, volumes)

        // Show active order card only when service is active and order exists.
        if (serviceStarted && activeOrder != null) {
            binding.kitchenOrderContainer.visibility = View.VISIBLE
            binding.kitchenOrderChildName.text = activeOrder?.name
            binding.kitchenOrderMealName.text = activeOrder?.meal

            val dietaryText = activeOrder
                ?.dietaryRequirements
                ?.filter { it.isNotBlank() && !it.equals("No known dietary requirements", ignoreCase = true) }
                ?.joinToString(separator = " • ")
                ?.takeIf { it.isNotBlank() }

            if (dietaryText.isNullOrBlank()) {
                binding.kitchenOrderDietaryRequirements.visibility = View.GONE
            } else {
                binding.kitchenOrderDietaryRequirements.text = dietaryText
                binding.kitchenOrderDietaryRequirements.visibility = View.VISIBLE
            }

            binding.mealServedButton.isEnabled = true
        } else {
            binding.kitchenOrderContainer.visibility = View.GONE
            binding.kitchenOrderDietaryRequirements.visibility = View.GONE
        }

        // Reorder major kitchen sections based on whether check-in has begun.
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

        // Reset scroll position when re-rendering kitchen dashboard.
        binding.appScrollView.scrollTo(0, 0)
    }

    // Renders child dashboard state machine and per-step UI transitions.
    private fun renderChildView() {
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
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

        // Gate child flow while service is not started on kitchen tablet.
        if (!serviceStarted || servicePausedByKitchen) {
            mealTimeStarted = false
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.IDLE
            binding.childServiceGateText.text = if (servicePausedByKitchen) {
                getString(R.string.service_status_paused)
            } else {
                getString(R.string.child_waiting_for_service)
            }
            binding.childServiceGateText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.contentSubtitle.visibility = View.GONE
            binding.startMealTimeButton.visibility = View.GONE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        // Service is live, so enable child interaction entry point.
        binding.childServiceGateText.text = getString(R.string.child_service_live)
        binding.childServiceGateText.setTextColor(ContextCompat.getColor(this, R.color.child_green))
        binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.childServiceGateText.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.startMealTimeButton.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

        // Before meal-time begins, keep step container hidden.
        if (!mealTimeStarted) {
            childScreen = ChildScreen.IDLE
            binding.contentTitle.visibility = View.VISIBLE
            binding.contentSubtitle.visibility = View.VISIBLE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        binding.childStepContainer.visibility = View.VISIBLE

        // Route to the appropriate child step renderer.
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

            ChildScreen.SUCCESS -> renderSuccessStep(binding, activeOrder)
        }

        // Show waiting overlay after success when returning to class selection.
        val shouldShowOverlay = showWaitingOverlayAfterConfirm && activeOrder != null &&
            (childScreen == ChildScreen.CLASS_SELECTION || childScreen == ChildScreen.NAME_SELECTION)
        binding.waitingOverlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
    }
}
