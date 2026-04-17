package com.techandthat.slpmealselection

import android.view.View
import androidx.core.content.ContextCompat
import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.ui.renderClassSelectionStep
import com.techandthat.slpmealselection.ui.renderNameSelectionStep
import com.techandthat.slpmealselection.ui.renderSuccessStep

// Extension function for rendering the child tablet dashboard and step transitions.
internal fun MainActivity.renderChildView() {
    binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))
    binding.headerTitle.text = getString(R.string.header_service_ongoing)
    binding.headerLogo.visibility = View.VISIBLE
    binding.headerLogo.setOnClickListener { returnToSetup() }
    binding.bloemfonteinLogo.visibility = View.VISIBLE
    binding.loadTodaysMealsButton.visibility = View.GONE
    binding.changeSchoolButton.visibility = View.GONE
    binding.endServiceButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE
    binding.contentTitle.visibility = View.GONE
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
        binding.childServiceGateText.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
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
                    childScreen = ChildScreen.SUCCESS
                    renderAppContent()
                }
            )
        }

        ChildScreen.SUCCESS -> renderSuccessStep(binding, activeOrder)
    }

    val shouldShowOverlay = showWaitingOverlayAfterConfirm && activeOrder != null &&
        (childScreen == ChildScreen.CLASS_SELECTION || childScreen == ChildScreen.NAME_SELECTION)
    binding.waitingOverlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
}
