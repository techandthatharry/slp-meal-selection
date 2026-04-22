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
    binding.bloemfonteinLogo.setOnClickListener(null)
    binding.bloemfonteinLogo.isClickable = false
    
    // Hide controls irrelevant to child mode.
    binding.loadTodaysMealsButton.visibility = View.GONE
    binding.endServiceButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE
    binding.contentTitle.visibility = View.GONE
    binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle_simple)

    // Switch between kitchen and child primary layout containers.
    binding.kitchenContent.visibility = View.GONE
    binding.childContent.visibility = View.VISIBLE
    
    // Control visibility of base titles depending on whether a meal selection session is active.
    binding.contentTitle.visibility = View.GONE
    binding.contentSubtitle.visibility = View.GONE

    // Safety Gate: Do not allow meal selection if the kitchen has paused or hasn't started service.
    if (!serviceStarted || servicePausedByKitchen) {
        binding.contentSubtitle.text = getString(R.string.child_waiting_for_service)
        binding.contentSubtitle.visibility = View.VISIBLE
        binding.startMealTimeButton.visibility = View.GONE
        binding.childStepContainer.visibility = View.GONE
        binding.waitingOverlay.visibility = View.GONE
        return
    }

    // Active Service State: Ensure mealTimeStarted is true and we're in a selection step.
    mealTimeStarted = true
    binding.contentSubtitle.visibility = View.GONE
    binding.startMealTimeButton.visibility = View.GONE

    binding.childStepContainer.visibility = View.VISIBLE

    // Handle the "Please wait" overlay.
    // This blocks the screen after the child confirms "I'M HERE" until the kitchen marks it as served.
    val isWaitingForKitchen = showWaitingOverlayAfterConfirm && activeOrder != null
    binding.waitingOverlay.visibility = if (isWaitingForKitchen) View.VISIBLE else View.GONE
    
    // If waiting for kitchen, we don't need to render the underlying steps again
    if (isWaitingForKitchen) return

    // Navigate between the different child selection screen states.
    // IDLE is treated identically to CLASS_SELECTION — merging them eliminates the
    // recursive renderChildView() call that caused a visible flicker on first entry.
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
                    // Set active order LOCALLY
                    activeOrder = child
                    childScreen = ChildScreen.SUCCESS
                    
                    // Notify the kitchen via Firestore.
                    repository.markCheckedIn(
                        entry = child,
                        onSuccess = { 
                            android.util.Log.d("SLP_SYNC", "Checked in ${child.name}")
                        },
                        onFailure = { error ->
                            repository.logErrorToFirebase("MarkCheckedIn_Auto", error, selectedSchool)
                        }
                    )
                    renderChildView()
                }
            )
        }

        ChildScreen.SUCCESS -> {
            renderSuccessStep(binding, activeOrder)
        }
    }
}
