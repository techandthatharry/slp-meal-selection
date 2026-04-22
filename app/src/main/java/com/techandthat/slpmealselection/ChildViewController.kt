package com.techandthat.slpmealselection

import android.view.View
import androidx.core.content.ContextCompat
import com.techandthat.slpmealselection.model.StudentScreen
import com.techandthat.slpmealselection.ui.renderClassSelectionStep
import com.techandthat.slpmealselection.ui.renderNameSelectionStep
import com.techandthat.slpmealselection.ui.renderSuccessStep

/**
 * Controller extension for MainActivity that manages the "Student-Facing Tablet" mode.
 * Handles the multi-step UI flow for students selecting their names and meals,
 * and manages service-state gating between the kitchen and student tablets.
 */

// Renders the student tablet dashboard and handles navigation between selection steps.
internal fun MainActivity.renderStudentView() {
    // Standardize header branding and appearance for student mode.
    binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
    binding.headerTitle.text = getString(R.string.header_service_ongoing)
    binding.headerLogo.visibility = View.VISIBLE

    // Logo click returns to setup/login for maintenance.
    binding.headerLogo.setOnClickListener { returnToSetup() }
    binding.bloemfonteinLogo.visibility = View.VISIBLE
    binding.bloemfonteinLogo.setOnClickListener(null)
    binding.bloemfonteinLogo.isClickable = false

    // Hide controls irrelevant to student mode.
    binding.loadTodaysMealsButton.visibility = View.GONE
    binding.endServiceButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE
    binding.contentTitle.visibility = View.GONE
    binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle_simple)

    // Switch between kitchen and student primary layout containers.
    binding.kitchenContent.visibility = View.GONE
    binding.childContent.visibility = View.VISIBLE

    binding.contentTitle.visibility = View.GONE
    binding.contentSubtitle.visibility = View.GONE

    // Safety Gate: Do not allow meal selection if the kitchen has paused or hasn't started service.
    if (!serviceStarted || servicePausedByKitchen) {
        binding.serviceStatusText.text = getString(R.string.child_waiting_for_service)
        binding.serviceStatusText.setTextColor(ContextCompat.getColor(this, R.color.kitchen_text_secondary))
        binding.serviceStatusText.visibility = View.VISIBLE
        binding.startMealTimeButton.visibility = View.GONE
        binding.childStepContainer.visibility = View.GONE
        binding.waitingOverlay.visibility = View.GONE
        return
    }

    // Clear the status strip — service is live.
    binding.serviceStatusText.visibility = View.GONE

    // Active Service State: ensure mealTimeStarted is true and we're in a selection step.
    mealTimeStarted = true
    binding.contentSubtitle.visibility = View.GONE
    binding.startMealTimeButton.visibility = View.GONE
    binding.childStepContainer.visibility = View.VISIBLE

    // Handle the "Please wait" overlay shown after a student confirms until the kitchen marks served.
    val isWaitingForKitchen = showWaitingOverlayAfterConfirm && activeOrder != null
    binding.waitingOverlay.visibility = if (isWaitingForKitchen) View.VISIBLE else View.GONE

    if (isWaitingForKitchen) return

    // Navigate between the different student selection screen states.
    // IDLE is treated identically to CLASS_SELECTION — merging them eliminates the
    // recursive renderStudentView() call that caused a visible flicker on first entry.
    when (studentScreen) {
        StudentScreen.IDLE, StudentScreen.CLASS_SELECTION -> {
            studentScreen = StudentScreen.CLASS_SELECTION
            renderClassSelectionStep(
                context = this,
                binding = binding,
                simulatedDatabase = simulatedDatabase,
                showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                onClassSelected = { selected ->
                    selectedClass = selected
                    studentScreen = StudentScreen.NAME_SELECTION
                    renderStudentView()
                }
            )
        }

        StudentScreen.NAME_SELECTION -> {
            renderNameSelectionStep(
                context = this,
                binding = binding,
                simulatedDatabase = simulatedDatabase,
                selectedClass = selectedClass,
                activeOrder = activeOrder,
                showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                onMissingClass = {
                    studentScreen = StudentScreen.CLASS_SELECTION
                    renderStudentView()
                },
                onStudentSelected = { student ->
                    activeOrder = student
                    studentScreen = StudentScreen.SUCCESS

                    repository.markCheckedIn(
                        entry = student,
                        onSuccess = {
                            android.util.Log.d("SLP_SYNC", "Checked in ${student.name}")
                        },
                        onFailure = { error ->
                            repository.logErrorToFirebase("MarkCheckedIn_Auto", error, selectedSchool)
                        }
                    )
                    renderStudentView()
                }
            )
        }

        StudentScreen.SUCCESS -> {
            renderSuccessStep(binding, activeOrder)
        }
    }
}
