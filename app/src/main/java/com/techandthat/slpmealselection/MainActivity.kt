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

/**
 * The primary activity and entry point for the SLP Meal Selection application.
 * This class coordinates the setup, kitchen, and child tablet workflows.
 * It manages the global application state and delegates UI rendering to specialized controllers.
 */
class MainActivity : ComponentActivity() {

    // Core infrastructure components for data, auth, and network.
    internal lateinit var binding: ActivityMainBinding
    internal val repository = ChildRecordsRepository()
    internal val authManager = AuthManager()
    internal val arborSyncService = ArborSyncService()

    // Static configuration and fallback data.
    internal val schools = listOf(
        "St Luke's Primary",
        "St Mary's Primary",
        "St Peter's Primary"
    )
    internal val initialDummyData = listOf(
        MealEntry("Liam Smith", "Reception", "Tomato Pasta"),
        MealEntry("Emma Jones", "Reception", "Fish & Chips"),
        MealEntry("Noah Williams", "Reception", "Tomato Pasta"),
        MealEntry("Olivia Brown", "Year 1", "Jacket Potato"),
        MealEntry("George Taylor", "Year 1", "Fish & Chips"),
        MealEntry("Mia Davies", "Year 2", "Jacket Potato")
    )

    // In-memory state management for the current session.
    internal val fallbackMealByChild = initialDummyData.associate { "${it.name}|${it.clazz}" to it.meal }
    internal val simulatedDatabase = mutableListOf<MealEntry>()

    // View state flags and session variables.
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
    internal var showingServiceStats = false

    // Real-time synchronization listeners.
    internal var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null
    internal var serviceStateListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Handler used to schedule listener reconnection attempts after network failures.
    internal val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Starts a real-time listener on the shared serviceState Firestore document.
     * The kitchen writes to this document; the child tablet reads it to unlock the UI.
     * On failure, schedules a retry after 5 seconds.
     */
    internal fun startServiceStateListener() {
        if (serviceStateListener != null) return
        android.util.Log.d("SLP_SYNC", "Starting service state listener for school: $selectedSchool")

        serviceStateListener = repository.listenForServiceState(
            schoolName = selectedSchool,
            onUpdate = { status ->
                val newStarted = status == "ACTIVE" || status == "PAUSED"
                val newPaused = status == "PAUSED"

                // Only act if state has actually changed, to avoid spurious re-renders.
                if (newStarted != serviceStarted || newPaused != servicePausedByKitchen) {
                    val wasStarted = serviceStarted
                    serviceStarted = newStarted
                    servicePausedByKitchen = newPaused

                    // On the child tablet, the records listener lifecycle is driven by service state.
                    if (!wasStarted && newStarted) startFirestoreListener()
                    if (wasStarted && !newStarted) stopFirestoreListener()

                    renderAppContent()
                }
            },
            onFailure = { error ->
                android.util.Log.e("SLP_SYNC", "Service state listener failed, retrying in 5s", error)
                repository.logErrorToFirebase("ServiceStateListener", error, selectedSchool)
                serviceStateListener = null
                reconnectHandler.postDelayed({ startServiceStateListener() }, 5_000L)
            }
        )
    }

    internal fun stopServiceStateListener() {
        serviceStateListener?.remove()
        serviceStateListener = null
    }

    /**
     * Starts a real-time listener to detect student check-ins from other tablets.
     * Automatically retries after 5 seconds if the connection drops.
     */
    internal fun startFirestoreListener() {
        if (firestoreListener != null) return

        android.util.Log.d("SLP_SYNC", "Starting Firestore listener for school: $selectedSchool")

        firestoreListener = repository.listenForCheckIns(
            schoolName = selectedSchool,
            onUpdate = { records ->
                android.util.Log.d("SLP_SYNC", "Firestore update received: ${records.size} records")
                // Update the global simulated database with fresh data from Firestore.
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
                        served = record.served,
                        checkedIn = record.checkedIn
                    )
                }

                simulatedDatabase.clear()
                simulatedDatabase.addAll(mapped)

                // Refresh the UI. The auto-pickup and overlay-clear logic is handled in the renderers.
                renderAppContent()
            },
            onFailure = { error ->
                android.util.Log.e("SLP_SYNC", "Firestore listener failed, retrying in 5s", error)
                repository.logErrorToFirebase("FirestoreListener", error, selectedSchool)
                firestoreListener = null
                // Only retry if the service is still active — avoids reconnecting after end-of-service.
                if (serviceStarted) {
                    reconnectHandler.postDelayed({ startFirestoreListener() }, 5_000L)
                }
            }
        )
    }

    internal fun stopFirestoreListener() {
        firestoreListener?.remove()
        firestoreListener = null
    }

    override fun onStart() {
        super.onStart()
        // Restart listeners if we're already logged in (e.g., returning from background).
        if (selectedTabletType != null) startServiceStateListener()
        if (serviceStarted) startFirestoreListener()
    }

    override fun onStop() {
        super.onStop()
        // Cancel any pending reconnect attempts to avoid memory leaks when backgrounded.
        reconnectHandler.removeCallbacksAndMessages(null)
        stopFirestoreListener()
        stopServiceStateListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    // Data model for capturing end-of-service summary statistics.
    internal data class ServiceStats(
        val mealsServed: Int,
        val studentsLoaded: Int,
        val arborUploaded: Int,
        val arborFailed: Int,
        val endedAtLabel: String,
        val mealsLoadedTimeLabel: String,
        val prepDurationMinutes: Int,
        val mealVolumes: Map<String, Int>
    )

    // Timing and analytics state.
    internal var latestServiceStats: ServiceStats? = null
    internal var mealsLoadedTime: Long? = null
    internal var serviceStartedTime: Long? = null

    // Activity lifecycle: Initializes binding, system UI, and splash screen.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure full-screen mode and UI helpers.
        hideSystemUi(window)
        setupSchoolSpinner(this, binding, schools)
        setupKeyboardSafeLoginScroll(binding)
        
        // Load branding assets from the assets folder.
        decodeAssetBitmap(assets, "SLP logo, no text.png")?.let {
            binding.splashLogo.setImageBitmap(it)
            binding.headerLogo.setImageBitmap(it)
        }
        decodeAssetBitmap(assets, "TechandThatLogoWhite.png")?.let {
            binding.footerLogo.setImageBitmap(it)
        }
        
        // Trigger initial splash screen transition.
        showSplashThenSetup(binding)
        
        // Initialize interactive listeners.
        bindListeners()
    }

    // Connects UI elements to their corresponding logic handlers across all application states.
    internal fun bindListeners() {
        // Setup Screen listeners.
        binding.loginButton.setOnClickListener { handleLogin() }

        // Kitchen Service controls.
        binding.startServiceButton.setOnClickListener {
            serviceStarted = true
            servicePausedByKitchen = false
            latestServiceStats = null
            serviceStartedTime = System.currentTimeMillis()
            startFirestoreListener()
            // Persist state to Firestore so the child tablet can react immediately.
            repository.setServiceState(
                schoolName = selectedSchool,
                status = "ACTIVE",
                onFailure = { error ->
                    repository.logErrorToFirebase("SetServiceState_Start", error, selectedSchool)
                }
            )
            renderAppContent()
        }

        // Shared Header/Navigation.
        binding.headerLogo.setOnClickListener { returnToSetup() }

        binding.pauseServiceHeaderButton.setOnClickListener {
            servicePausedByKitchen = !servicePausedByKitchen
            val newStatus = if (servicePausedByKitchen) "PAUSED" else "ACTIVE"
            repository.setServiceState(
                schoolName = selectedSchool,
                status = newStatus,
                onFailure = { error ->
                    repository.logErrorToFirebase("SetServiceState_Pause", error, selectedSchool)
                }
            )
            renderAppContent()
        }

        binding.endServiceButton.setOnClickListener { confirmAndEndService() }
        binding.changeSchoolButton.setOnClickListener { showChangeSchoolDialog() }

        // Data Loading for Kitchen.
        binding.loadTodaysMealsButton.setOnClickListener {
            if (isLoadingMeals || serviceStarted) return@setOnClickListener

            val hasPrepData = simulatedDatabase.any { !it.served }
            if (hasPrepData) {
                // Confirm before overwriting local unsaved data.
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

        // Child Mode Navigation.
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

        // Kitchen Order Fulfillment.
        binding.mealServedButton.setOnClickListener {
            val servedEntry = activeOrder ?: return@setOnClickListener

            // Optimistically update the UI.
            setMealServedLocally(servedEntry, served = true)
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = if (selectedClass.isNullOrBlank()) {
                ChildScreen.CLASS_SELECTION
            } else {
                ChildScreen.NAME_SELECTION
            }
            
            // Trigger high-level dispatcher to auto-pickup next student if available.
            renderAppContent()

            // Persist the change to Firestore.
            repository.markMealServed(
                entry = servedEntry,
                schoolName = selectedSchool,
                onSuccess = {
                    // Success is handled optimistically.
                },
                onFailure = { error ->
                    // Rollback UI on network/database failure.
                    setMealServedLocally(servedEntry, served = false)
                    activeOrder = servedEntry
                    firebaseStatusMessage = "Failed to save served meal"
                    android.widget.Toast.makeText(
                        this,
                        error.message ?: "Failed to mark meal served",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    renderAppContent()
                }
            )
        }

        // Stats Dashboard.
        binding.returnToDashboardButton.setOnClickListener {
            showingServiceStats = false
            latestServiceStats = null
            renderAppContent()
        }
    }

    // High-level UI dispatcher that switches between tablet mode views.
    internal fun renderAppContent() {
        // Automatically pick the next checked-in student for the kitchen if available.
        if (selectedTabletType == TabletType.KITCHEN && serviceStarted && activeOrder == null) {
            activeOrder = simulatedDatabase.firstOrNull { it.checkedIn && !it.served }
        }

        // Automatically clear the waiting overlay on the child tablet if the order has been served.
        if (selectedTabletType == TabletType.CHILD && activeOrder != null) {
            val currentOrderInDb = simulatedDatabase.find { it.documentId == activeOrder?.documentId }
            if (currentOrderInDb?.served == true) {
                activeOrder = null
                showWaitingOverlayAfterConfirm = false
            }
        }

        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView()
            TabletType.CHILD -> renderChildView()
            null -> Unit
        }
    }

    // Helper to resolve the radio group selection into a TabletType enum.
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
