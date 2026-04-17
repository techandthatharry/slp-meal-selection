package com.techandthat.slpmealselection

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.ui.MealPrepUi

// Extension functions for rendering and styling the kitchen dashboard.

// Applies solid-fill primary button style (blue background, white text, no stroke).
internal fun MainActivity.applyPrimaryButtonStyle(button: MaterialButton) {
    button.backgroundTintList = ColorStateList.valueOf(
        ContextCompat.getColor(this, R.color.kitchen_success)
    )
    button.setTextColor(ContextCompat.getColor(this, R.color.white))
    button.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
    button.strokeWidth = 0
    button.alpha = 1f
}

// Applies outlined secondary button style (white background, grey border, grey text).
internal fun MainActivity.applySecondaryButtonStyle(button: MaterialButton) {
    button.backgroundTintList = ColorStateList.valueOf(
        ContextCompat.getColor(this, R.color.button_secondary_bg)
    )
    button.setTextColor(ContextCompat.getColor(this, R.color.button_secondary_text))
    button.iconTint = ColorStateList.valueOf(
        ContextCompat.getColor(this, R.color.button_secondary_text)
    )
    button.strokeColor = ColorStateList.valueOf(
        ContextCompat.getColor(this, R.color.button_secondary_border)
    )
    button.strokeWidth = resources.getDimensionPixelSize(R.dimen.button_stroke_width)
    button.alpha = 1f
}

// Renders kitchen dashboard state, button availability, and prep summary.
internal fun MainActivity.renderKitchenView() {
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

    // Update service status messaging and prep card title.
    val statusText = when {
        serviceStarted && servicePausedByKitchen -> getString(R.string.service_status_paused)
        serviceStarted -> getString(R.string.service_status_started)
        !hasPrepData -> getString(R.string.service_status_ready_to_load)
        else -> ""
    }
    binding.serviceStatusText.text = statusText
    binding.serviceStatusText.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
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

    binding.startServiceButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
    binding.loadTodaysMealsButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
    binding.pauseServiceButton.visibility = if (serviceStarted) View.VISIBLE else View.GONE

    binding.startServiceButton.isEnabled = !serviceStarted && hasPrepData
    if (binding.startServiceButton.isEnabled) {
        applyPrimaryButtonStyle(binding.startServiceButton)
    } else {
        applySecondaryButtonStyle(binding.startServiceButton)
    }

    binding.pauseServiceButton.text = if (servicePausedByKitchen) {
        getString(R.string.resume_service_button_with_icon)
    } else {
        getString(R.string.pause_service_button_with_icon)
    }

    val canLoadMeals = !serviceStarted && !isLoadingMeals
    binding.loadTodaysMealsButton.isEnabled = canLoadMeals
    if (!hasPrepData && canLoadMeals) {
        applyPrimaryButtonStyle(binding.loadTodaysMealsButton)
    } else {
        applySecondaryButtonStyle(binding.loadTodaysMealsButton)
    }
    binding.loadTodaysMealsButton.text = getString(R.string.load_todays_meals)

    // Show brief loading indicator while checking Firebase cache.
    if (isLoadingMeals) {
        binding.prepLoadingText.visibility = View.VISIBLE
        binding.prepLoadingText.text = getString(R.string.loading_todays_meals)
        binding.prepLoadingProgress.visibility = View.VISIBLE
        binding.prepLoadingProgress.progress = 0
    } else {
        binding.prepLoadingText.visibility = View.GONE
        binding.prepLoadingProgress.visibility = View.GONE
    }

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

    MealPrepUi.renderMealPrepRows(this, binding, volumes)

    if (serviceStarted && activeOrder != null) {
        binding.kitchenOrderContainer.visibility = View.VISIBLE
        binding.kitchenOrderChildName.text = activeOrder?.name
        binding.kitchenOrderMealName.text = activeOrder?.meal

        val dietaryText = activeOrder
            ?.dietaryRequirements
            ?.filter {
                it.isNotBlank() && !it.equals("No known dietary requirements", ignoreCase = true)
            }
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

    // Handle Service Stats Card
    val stats = latestServiceStats
    if (stats != null && !serviceStarted && simulatedDatabase.isEmpty()) {
        binding.serviceStatsCard.visibility = View.VISIBLE
        binding.serviceStatsLine1.text = getString(R.string.stats_meals_served, stats.mealsServed)
        binding.serviceStatsLine2.text = getString(R.string.stats_students_loaded, stats.studentsLoaded)
        binding.serviceStatsLine3.text = getString(R.string.stats_arbor_uploaded, stats.arborUploaded)
        binding.serviceStatsLine4.text = getString(R.string.stats_service_ended_at, stats.endedAtLabel)
    } else {
        binding.serviceStatsCard.visibility = View.GONE
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

    binding.appScrollView.scrollTo(0, 0)
}
