package com.techandthat.slpmealselection

import android.view.View
import androidx.appcompat.app.AlertDialog
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.TabletType

// Extension functions managing login, school selection, service confirmation, and navigation.

// Validates login inputs and transitions into the selected tablet mode.
internal fun MainActivity.handleLogin() {
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
    selectedSchool = binding.initialSchoolSpinner.selectedItem?.toString()
        .orEmpty()
        .ifBlank { schools.first() }
    binding.setupErrorText.visibility = View.GONE
    binding.setupContainer.visibility = View.GONE
    binding.appContainer.visibility = View.VISIBLE

    authenticateThenLoadRoster()
    renderAppContent()
}

// Verifies static kiosk credentials for the selected tablet mode.
internal fun isCredentialValid(
    tabletType: TabletType,
    username: String,
    password: String
): Boolean = when (tabletType) {
    TabletType.KITCHEN -> username == "k" && password == "k"
    TabletType.CHILD -> username == "c" && password == "c"
}

// Displays setup validation error text under login controls.
internal fun MainActivity.showSetupError(message: String) {
    binding.setupErrorText.text = message
    binding.setupErrorText.visibility = View.VISIBLE
}

// Returns app to setup screen and clears mode-specific transient state.
internal fun MainActivity.returnToSetup() {
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

// Opens confirmation dialog before ending service and deleting records.
internal fun MainActivity.confirmAndEndService() {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.confirm_end_service_title))
        .setMessage(getString(R.string.confirm_end_service_message))
        .setNegativeButton(getString(R.string.cancel), null)
        .setPositiveButton(getString(R.string.end_service)) { _, _ ->
            endServiceAndDeleteRecords()
        }
        .show()
}

// Shows school picker dialog and reloads data scoped to the newly selected school.
internal fun MainActivity.showChangeSchoolDialog() {
    val selectedIndex = schools.indexOf(selectedSchool).takeIf { it >= 0 } ?: 0
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.change_school))
        .setSingleChoiceItems(schools.toTypedArray(), selectedIndex) { dialog, which ->
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
