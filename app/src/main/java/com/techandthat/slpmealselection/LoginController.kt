package com.techandthat.slpmealselection

import android.view.View
import androidx.appcompat.app.AlertDialog
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.TabletType

/**
 * Controller extension for MainActivity that manages the initial setup, login,
 * and high-level configuration of the application (e.g., choosing school or tablet mode).
 */

// Processes the login form, validates credentials, and initializes the selected tablet mode.
internal fun MainActivity.handleLogin() {
    // Determine which mode (Kitchen or Child) the user has selected.
    val tabletType = resolveTabletType() ?: run {
        showSetupError(getString(R.string.select_tablet_type_error))
        return
    }

    // Extract and normalize login credentials.
    val username = binding.usernameInput.text?.toString()?.trim()?.lowercase().orEmpty()
    val password = binding.passwordInput.text?.toString()?.trim()?.lowercase().orEmpty()

    // Validate against hardcoded kiosk credentials.
    if (!isCredentialValid(tabletType, username, password)) {
        showSetupError(getString(R.string.invalid_credentials_error))
        return
    }

    // Initialize session variables and transition the UI to the main app dashboard.
    selectedTabletType = tabletType
    selectedSchool = binding.initialSchoolSpinner.selectedItem?.toString()
        .orEmpty()
        .ifBlank { schools.first() }
    binding.setupErrorText.visibility = View.GONE
    binding.setupContainer.visibility = View.GONE
    binding.appContainer.visibility = View.VISIBLE

    // Bootstrap data loading from Firestore.
    authenticateThenLoadRoster()
    renderAppContent()
}

// Simple credential check for kiosk mode (hardcoded for demonstration/POC).
internal fun isCredentialValid(
    tabletType: TabletType,
    username: String,
    password: String
): Boolean = when (tabletType) {
    TabletType.KITCHEN -> username == "k" && password == "k"
    TabletType.CHILD -> username == "c" && password == "c"
}

// Displays a validation error message on the setup screen.
internal fun MainActivity.showSetupError(message: String) {
    binding.setupErrorText.text = message
    binding.setupErrorText.visibility = View.VISIBLE
}

// Resets the application state and returns the user to the initial login/setup screen.
internal fun MainActivity.returnToSetup() {
    binding.appContainer.visibility = View.GONE
    binding.setupContainer.visibility = View.VISIBLE

    // Clear login fields and mode selection.
    selectedTabletType = null
    binding.tabletTypeGroup.clearCheck()
    binding.usernameInput.text?.clear()
    binding.passwordInput.text?.clear()
    binding.setupErrorText.visibility = View.GONE

    // Reset all transient session data to ensure a clean slate.
    activeOrder = null
    showWaitingOverlayAfterConfirm = false
    selectedClass = null
    mealTimeStarted = false
    childScreen = ChildScreen.IDLE
}

// Displays a confirmation prompt before ending the service and initiating the Arbor sync.
internal fun MainActivity.confirmAndEndService() {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.confirm_end_service_title))
        .setMessage(getString(R.string.confirm_end_service_message))
        .setNegativeButton(getString(R.string.cancel), null)
        .setPositiveButton(getString(R.string.end_service)) { _, _ ->
            // Proceed to upload data and wipe records.
            endServiceAndDeleteRecords()
        }
        .show()
}

// Displays a dialog allowing the user to switch the active school context.
internal fun MainActivity.showChangeSchoolDialog() {
    val selectedIndex = schools.indexOf(selectedSchool).takeIf { it >= 0 } ?: 0
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.change_school))
        .setSingleChoiceItems(schools.toTypedArray(), selectedIndex) { dialog, which ->
            // Update school selection and reset data.
            selectedSchool = schools[which]
            simulatedDatabase.clear()
            activeOrder = null
            loadChildRecordsFromFirestore()
            renderAppContent()
            dialog.dismiss()
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}
