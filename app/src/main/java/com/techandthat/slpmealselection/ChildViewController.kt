package com.techandthat.slpmealselection

import android.view.View
import androidx.core.content.ContextCompat
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.ui.renderClassSelectionStep
import com.techandthat.slpmealselection.ui.renderNameSelectionStep
import com.techandthat.slpmealselection.ui.renderSuccessStep

/**
 * Controller extension for MainActivity that manages the "Child-Facing Tablet" mode.
 * Handles the multi-step UI flow for students selecting their names and meals,
 * and manages service-state gating between the kitchen and child tablets.
 */

// Renders the child tablet dashboard and handles navigation between selection steps.
internal fun MainActivity.renderChildView() {
    // Standardize header branding and appearance for child mode.
    binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
    binding.headerTitle.text = getString(R.string.header_service_ongoing)
    binding.headerLogo.visibility = View.VISIBLE
    
    // Logo click returns to setup/login for maintenance.
    binding.headerLogo.setOnClickListener { returnToSetup() }
    binding.bloemfonteinLogo.visibility = View.VISIBLE
    
    // Hide controls irrelevant to child mode.
    binding.loadTodaysMealsButton.visibility = View.GONE
    binding.changeSchoolButton.visibility = View.GONE
    binding.endServiceButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE
    binding.contentTitle.visibility = View.GONE
    binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle_simple)

    // Switch between kitchen and child primary layout containers.
    binding.kitchenContent.visibility = View.GONE
    binding.childContent.visibility = View.VISIBLE
    
    // Control visibility of base titles depending on whether a meal selection session is active.
    binding.contentTitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
    binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

    // Safety Gate: Do not allow meal selection if the kitchen has paused or hasn't started service.
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
        binding.childServiceGateText.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        
        binding.contentSubtitle.visibility = View.GONE
        binding.startMealTimeButton.visibility = View.GONE
        binding.childStepContainer.visibility = View.GONE
        binding.waitingOverlay.visibility = View.GONE
        return
    }

    // Active Service State: Display start button if not already in the selection flow.
    binding.childServiceGateText.text = getString(R.string.child_service_live)
    binding.childServiceGateText.setTextColor(ContextCompat.getColor(this, R.color.child_green))
    binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
    binding.childServiceGateText.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
    binding.startMealTimeButton.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

    // Transition to selection steps if session is started.
    if (!mealTimeStarted) {
        childScreen = ChildScreen.IDLE
        binding.contentTitle.visibility = View.VISIBLE
        binding.contentSubtitle.visibility = View.VISIBLE
        binding.childStepContainer.visibility = View.GONE
        binding.waitingOverlay.visibility = View.GONE
        return
    }

    binding.childStepContainer.visibility = View.VISIBLE

    // Navigate between the different child selection screen states.
    when (childScreen) {
        ChildScreen.IDLE, ChildScreen.CLASS_SELECTION -> {
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
                    // Notify the kitchen immediately via Firestore.
                    repository.markCheckedIn(
                        entry = child,
                        onSuccess = { /* Success reflected via listener */ },
                        onFailure = { error ->
                            repository.logErrorToFirebase("MarkCheckedIn_Auto", error, selectedSchool)
                        }
                    )
                    childScreen = ChildScreen.SUCCESS
                    renderAppContent()
                }
            )
        }

        ChildScreen.SUCCESS -> renderSuccessStep(binding, activeOrder)
    }

    // Handle the "Please wait" overlay that blocks the child screen while the kitchen prepares the plate.
    val shouldShowOverlay = showWaitingOverlayAfterConfirm && activeOrder != null &&
        (childScreen == ChildScreen.CLASS_SELECTION || childScreen == ChildScreen.NAME_SELECTION)
    binding.waitingOverlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
}
