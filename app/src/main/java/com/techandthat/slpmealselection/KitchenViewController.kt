package com.techandthat.slpmealselection

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.ui.MealPrepUi

/**
 * Controller extension for MainActivity that manages the "Kitchen-Facing Tablet" mode.
 * Handles the rendering of the meal preparation dashboard, service controls,
 * real-time order notifications, and post-service statistics.
 */

// Applies a consistent solid-fill primary button style used for "Success" or "Action" triggers.
internal fun MainActivity.applyPrimaryButtonStyle(button: MaterialButton) {
    button.backgroundTintList = ColorStateList.valueOf(
        ContextCompat.getColor(this, R.color.kitchen_success)
    )
    button.setTextColor(ContextCompat.getColor(this, R.color.white))
    button.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
    button.strokeWidth = 0
    button.alpha = 1f
}

// Applies a consistent outlined secondary button style used for "Secondary" or "Settings" triggers.
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

// Main rendering engine for the kitchen dashboard UI.
internal fun MainActivity.renderKitchenView() {
    // Update header branding for the kitchen environment.
    binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.kitchen_header_bg))

    // Determine state based on whether student check-in has occurred.
    val hasCheckInStarted = activeOrder != null || simulatedDatabase.any { it.served }
    val shouldHideLoadAndStart = serviceStarted
    val outstandingMeals = simulatedDatabase.filterNot { it.served }
    val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
    val hasPrepData = outstandingMeals.isNotEmpty()

    // Dynamically update the header title based on current workflow stage.
    binding.headerTitle.text = when {
        serviceStarted && hasCheckInStarted -> getString(R.string.header_service_ongoing)
        serviceStarted -> getString(R.string.header_service_ongoing)
        showingServiceStats -> getString(R.string.header_summary_dashboard)
        else -> getString(R.string.header_prep_dashboard)
    }
    binding.headerLogo.visibility = View.VISIBLE
    binding.bloemfonteinLogo.visibility = View.VISIBLE

    // Delegate to stats view if service has ended and user is viewing the summary.
    if (showingServiceStats) {
        renderServiceStatsView()
        return
    }

    // Set content titles for the preparation phase.
    binding.contentTitle.text = if (serviceStarted) "SERVICE IN PROGRESS" else getString(R.string.kitchen_dashboard_title)
    binding.contentSubtitle.visibility = View.GONE

    // Toggle container visibilities for the kitchen dashboard.
    binding.kitchenContent.visibility = View.VISIBLE
    binding.serviceEndedContent.visibility = View.GONE
    binding.childContent.visibility = View.GONE

    // Update the visual status indicator (Live/Paused/Ready).
    val statusText = when {
        serviceStarted && servicePausedByKitchen -> getString(R.string.service_status_paused)
        serviceStarted -> getString(R.string.service_status_started)
        !hasPrepData -> getString(R.string.service_status_ready_to_load)
        else -> ""
    }
    binding.serviceStatusText.text = statusText
    binding.serviceStatusText.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
    
    // Switch label depending on whether we are in "Prep Mode" or "Serving Mode".
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

    // Manage visibility and interactivity of service control buttons.
    binding.startServiceButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
    binding.loadTodaysMealsButton.visibility = if (shouldHideLoadAndStart) View.GONE else View.VISIBLE
    binding.pauseServiceHeaderButton.visibility = if (serviceStarted) View.VISIBLE else View.GONE

    binding.startServiceButton.isEnabled = !serviceStarted && hasPrepData
    if (binding.startServiceButton.isEnabled) {
        applyPrimaryButtonStyle(binding.startServiceButton)
    } else {
        applySecondaryButtonStyle(binding.startServiceButton)
    }

    binding.pauseServiceHeaderButton.text = if (servicePausedByKitchen) {
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

    // Handle loading state overlays for network operations.
    if (isLoadingMeals) {
        binding.prepLoadingText.visibility = View.VISIBLE
        binding.prepLoadingProgress.visibility = View.VISIBLE
        binding.prepLoadingProgress.isIndeterminate = true
    } else {
        binding.prepLoadingText.visibility = View.GONE
        binding.prepLoadingProgress.visibility = View.GONE
    }

    // Secondary navigation buttons for resetting the app or ending the day.
    binding.changeSchoolButton.visibility = View.VISIBLE
    binding.endServiceButton.visibility = View.VISIBLE
    binding.changeSchoolButton.isEnabled = !serviceStarted
    binding.endServiceButton.isEnabled = serviceStarted || simulatedDatabase.isNotEmpty() || activeOrder != null

    // Display Firestore/Arbor status messages (errors or sync progress).
    if (firebaseStatusMessage.isNullOrBlank() || isLoadingMeals) {
        binding.firebaseStatusText.visibility = View.GONE
    } else {
        binding.firebaseStatusText.text = firebaseStatusMessage
        binding.firebaseStatusText.visibility = View.VISIBLE
    }

    // Render the meal preparation summary list (e.g., "5x Meat, 3x Veg").
    MealPrepUi.renderMealPrepRows(this, binding, volumes)

    // Render the "Active Order" card when a student has selected their meal on the child tablet.
    if (serviceStarted && activeOrder != null) {
        binding.kitchenOrderContainer.visibility = View.VISIBLE
        binding.kitchenOrderChildName.text = activeOrder?.name
        binding.kitchenOrderMealName.text = activeOrder?.meal

        // Format dietary requirements into a bulleted string, highlighting them in red if present.
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
            binding.kitchenOrderDietaryRequirements.text = "DIETARY: $dietaryText"
            binding.kitchenOrderDietaryRequirements.visibility = View.VISIBLE
        }

        binding.mealServedButton.isEnabled = true
    } else {
        binding.kitchenOrderContainer.visibility = View.GONE
        binding.kitchenOrderDietaryRequirements.visibility = View.GONE
    }

    // Render a mini summary card if statistics from the previous session are available.
    val stats = latestServiceStats
    if (stats != null && !serviceStarted && simulatedDatabase.isEmpty()) {
        binding.serviceStatsCard.visibility = View.VISIBLE
        binding.serviceStatsLine1.text = getString(R.string.stats_meals_served, stats.mealsServed)
        binding.serviceStatsLine2.text = getString(R.string.stats_students_loaded, stats.studentsLoaded)
        binding.serviceStatsLine3.text = "Meals loaded at: ${stats.mealsLoadedTimeLabel}"
        binding.serviceStatsLine4.text = "Prep time: ${stats.prepDurationMinutes} mins"
    } else {
        binding.serviceStatsCard.visibility = View.GONE
    }

    // Re-order layout sections dynamically so the "Active Order" appears at the top when live.
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

    // Ensure the view is scrolled to the top after re-rendering.
    binding.appScrollView.scrollTo(0, 0)
}

// Renders the full-screen "Service Statistics" dashboard after the kitchen syncs to Arbor.
internal fun MainActivity.renderServiceStatsView() {
    binding.contentTitle.text = getString(R.string.todays_service_stats_title)
    binding.contentSubtitle.visibility = View.GONE
    binding.kitchenContent.visibility = View.GONE
    binding.childContent.visibility = View.GONE
    binding.serviceEndedContent.visibility = View.VISIBLE

    // Hide dashboard-specific control buttons.
    binding.endServiceButton.visibility = View.GONE
    binding.changeSchoolButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE

    val stats = latestServiceStats
    if (stats != null) {
        // Populate the 6 summary cards with session data.
        binding.statsTodayValue.text = stats.mealsServed.toString()
        
        binding.statsWeekValue.text = stats.weekTotal.toString()
        binding.statsWeekLabel.text = getString(R.string.stats_week)
        
        binding.statsMonthValue.text = stats.monthTotal.toString()
        binding.statsMonthLabel.text = getString(R.string.stats_month)
        
        binding.statsLoadedAtValue.text = stats.mealsLoadedTimeLabel
        binding.statsPrepTimeValue.text = "${stats.prepDurationMinutes}m"
        
        // Calculate Arbor synchronization success percentage.
        val syncPercent = if (stats.studentsLoaded > 0) {
            (stats.arborUploaded * 100) / stats.studentsLoaded
        } else 100
        binding.statsArborValue.text = getString(R.string.percent_format, syncPercent)
        
        // Color-code the sync status (Red for failures, Green for success).
        if (syncPercent < 100) {
            binding.statsArborValue.setTextColor(ContextCompat.getColor(this, R.color.kitchen_danger))
        } else {
            binding.statsArborValue.setTextColor(ContextCompat.getColor(this, R.color.kitchen_success))
        }

        // Render the per-meal volume bars.
        renderMealVolumeBars(stats.mealVolumes)
    }

    // Render the weekly trend graph.
    renderStatsGraph()
    binding.appScrollView.post {
        binding.appScrollView.scrollTo(0, 0)
    }
}

// Dynamically builds a horizontal bar chart showing how many of each meal were served.
private fun MainActivity.renderMealVolumeBars(volumes: Map<String, Int>) {
    binding.mealVolumeGraphContainer.removeAllViews()
    if (volumes.isEmpty()) return

    val maxVolume = volumes.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val density = resources.displayMetrics.density

    volumes.forEach { (meal, count) ->
        val barWrapper = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }

        val valueLabel = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = count.toString()
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@renderMealVolumeBars, R.color.kitchen_text_secondary))
        }

        val bar = android.view.View(this).apply {
            val barHeightDp = (count.toFloat() / maxVolume * 120).coerceAtLeast(4f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.stats_bar_width),
                (barHeightDp * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@renderMealVolumeBars, R.drawable.stats_bar_bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@renderMealVolumeBars, R.color.slp_blue)
            )
        }

        val label = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = meal.take(5) // Truncate meal names for compact display.
            textSize = 9f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@renderMealVolumeBars, R.color.kitchen_text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        barWrapper.addView(valueLabel)
        barWrapper.addView(bar)
        barWrapper.addView(label)
        binding.mealVolumeGraphContainer.addView(barWrapper)
    }
}

// Dynamically builds the weekly performance graph at the bottom of the summary dashboard.
internal fun MainActivity.renderStatsGraph() {
    binding.statsGraphContainer.removeAllViews()
    
    // Provide a mix of mock data for history and real data for today.
    val dailyCounts = listOf(42, 38, 45, 41, 39, 0, latestServiceStats?.mealsServed ?: 0)
    val maxCount = dailyCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val density = resources.displayMetrics.density

    dailyCounts.forEachIndexed { index, count ->
        val barWrapper = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }

        // Add numerical value label above each bar.
        if (count > 0) {
            val valueLabel = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = count.toString()
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@renderStatsGraph, R.color.kitchen_text_secondary))
                android.view.View.TEXT_ALIGNMENT_CENTER
            }
            barWrapper.addView(valueLabel)
        }

        val bar = android.view.View(this).apply {
            // Calculate proportional height based on the week's maximum.
            val barHeightDp = (count.toFloat() / maxCount * 120).coerceAtLeast(4f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.stats_bar_width),
                (barHeightDp * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@renderStatsGraph, R.drawable.stats_bar_bg)
            
            // Highlight today's bar in SLP Blue, history in grey.
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (index == dailyCounts.size - 1) ContextCompat.getColor(this@renderStatsGraph, R.color.slp_blue)
                else ContextCompat.getColor(this@renderStatsGraph, R.color.button_secondary_border)
            )
        }

        val label = android.widget.TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = when(index) {
                0 -> "Mon"; 1 -> "Tue"; 2 -> "Wed"; 3 -> "Thu"; 4 -> "Fri"; 5 -> "Sat"; 6 -> "Sun"
                else -> ""
            }
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@renderStatsGraph, R.color.kitchen_text_secondary))
        }

        barWrapper.addView(bar)
        barWrapper.addView(label)
        binding.statsGraphContainer.addView(barWrapper)
    }
}
