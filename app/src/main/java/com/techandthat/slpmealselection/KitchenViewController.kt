package com.techandthat.slpmealselection

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.ui.MealPrepUi
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

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
    binding.bloemfonteinLogo.isClickable = !serviceStarted
    binding.bloemfonteinLogo.alpha = if (serviceStarted) 0.4f else 1f

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
        serviceStarted && activeOrder == null -> getString(R.string.service_status_started)
        serviceStarted && activeOrder != null -> ""
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
    
    // All status strip messages use a consistent orange — visually distinct from
    // both error red and success green, so attention is drawn without alarm.
    binding.serviceStatusText.setTextColor(
        ContextCompat.getColor(this, R.color.kitchen_warning)
    )

    // Task 6: Hide the "Meals to be served" summary card during active service;
    // it is only useful in the prep phase before service starts.
    binding.prepListContainer.visibility = if (serviceStarted) View.GONE else View.VISIBLE

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
        binding.prepLoadingProgress.visibility = View.VISIBLE
        binding.prepLoadingText.text = loadingMessage ?: getString(R.string.loading_todays_meals)
        binding.prepLoadingText.visibility = View.VISIBLE
    } else {
        binding.prepLoadingText.visibility = View.GONE
        binding.prepLoadingText.text = ""
        binding.prepLoadingProgress.visibility = View.GONE
        loadingMessage = null
    }

    // Secondary navigation button for ending the day.
    binding.endServiceButton.visibility = View.VISIBLE
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
            binding.kitchenOrderDietaryRequirements.text = getString(R.string.dietary_clear)
            binding.kitchenOrderDietaryRequirements.setTextColor(
                ContextCompat.getColor(this, R.color.kitchen_safe)
            )
            binding.kitchenOrderDietaryRequirements.visibility = View.VISIBLE
        } else {
            binding.kitchenOrderDietaryRequirements.text = getString(R.string.dietary_label, dietaryText)
            binding.kitchenOrderDietaryRequirements.setTextColor(
                ContextCompat.getColor(this, R.color.kitchen_danger)
            )
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
        binding.serviceStatsLine3.text = getString(R.string.stats_loaded_at, stats.mealsLoadedTimeLabel)
        binding.serviceStatsLine4.text = getString(R.string.stats_prep_time, stats.prepDurationMinutes)
    } else {
        binding.serviceStatsCard.visibility = View.GONE
    }

    // Re-order layout sections so the "Active Order" appears at the top when live.
    // Guard with an index check so removeView/addView (which trigger a full layout pass)
    // only fire when the order actually needs to change, not on every render.
    val orderCardCurrentlyAtTop =
        binding.kitchenContent.indexOfChild(binding.kitchenOrderContainer) == 0
    if (hasCheckInStarted && !orderCardCurrentlyAtTop) {
        binding.kitchenContent.removeView(binding.kitchenOrderContainer)
        binding.kitchenContent.addView(binding.kitchenOrderContainer, 0)
        binding.kitchenContent.removeView(binding.prepAndLoadRow)
        binding.kitchenContent.addView(binding.prepAndLoadRow)
    } else if (!hasCheckInStarted && orderCardCurrentlyAtTop) {
        binding.kitchenContent.removeView(binding.prepAndLoadRow)
        binding.kitchenContent.addView(binding.prepAndLoadRow, 0)
        binding.kitchenContent.removeView(binding.kitchenOrderContainer)
        binding.kitchenContent.addView(binding.kitchenOrderContainer)
    }

    // Collapse the status strip to zero height when no message is shown.
    val anyStripVisible =
        binding.serviceStatusText.visibility == View.VISIBLE ||
        binding.prepLoadingText.visibility == View.VISIBLE ||
        binding.prepLoadingProgress.visibility == View.VISIBLE ||
        binding.firebaseStatusText.visibility == View.VISIBLE
    binding.statusStrip.visibility = if (anyStripVisible) View.VISIBLE else View.GONE

    // Restore kitchen header to full height (student mode may have reduced it).
    val kitchenHeaderHeightPx = (160 * resources.displayMetrics.density).toInt()
    if (binding.headerBar.layoutParams.height != kitchenHeaderHeightPx) {
        binding.headerBar.layoutParams = binding.headerBar.layoutParams.apply {
            height = kitchenHeaderHeightPx
        }
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
    binding.pauseServiceHeaderButton.visibility = View.GONE

    val stats = latestServiceStats
    if (stats != null) {
        // Populate the 6 summary cards with session data.
        binding.statsTodayValue.text = stats.mealsServed.toString()
        
        // Historical totals are not yet stored — display a neutral placeholder.
        binding.statsWeekValue.text = "—"
        binding.statsWeekLabel.text = getString(R.string.stats_week)

        binding.statsMonthValue.text = "—"
        binding.statsMonthLabel.text = getString(R.string.stats_month)
        
        binding.statsLoadedAtValue.text = stats.mealsLoadedTimeLabel
        binding.statsPrepTimeValue.text = getString(R.string.stats_prep_time_value, stats.prepDurationMinutes)
        
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
        val barWrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val valueLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = count.toString()
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@renderMealVolumeBars, R.color.kitchen_text_secondary))
        }

        val bar = View(this).apply {
            val barHeightDp = (count.toFloat() / maxVolume * 120).coerceAtLeast(4f)
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.stats_bar_width),
                (barHeightDp * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@renderMealVolumeBars, R.drawable.stats_bar_bg)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@renderMealVolumeBars, R.color.slp_blue)
            )
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = meal.take(5) // Truncate meal names for compact display.
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@renderMealVolumeBars, R.color.kitchen_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        barWrapper.addView(valueLabel)
        barWrapper.addView(bar)
        barWrapper.addView(label)
        binding.mealVolumeGraphContainer.addView(barWrapper)
    }
}

// Renders the weekly trends graph area. Historical data is not yet persisted, so this
// shows only today's real count alongside an explanatory placeholder for prior days.
internal fun MainActivity.renderStatsGraph() {
    binding.statsGraphContainer.removeAllViews()
    val todayCount = latestServiceStats?.mealsServed ?: 0
    val density = resources.displayMetrics.density

    // Show one real bar for today and greyed-out placeholders for Mon–Fri history.
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Today")
    val counts = listOf(0, 0, 0, 0, 0, todayCount)
    val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1

    days.forEachIndexed { index, dayLabel ->
        val count = counts[index]
        val isToday = index == days.lastIndex

        val barWrapper = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        if (isToday && count > 0) {
            val valueLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = count.toString()
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@renderStatsGraph, R.color.kitchen_text_secondary))
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            barWrapper.addView(valueLabel)
        }

        val bar = View(this).apply {
            val barHeightDp = if (isToday && count > 0) {
                (count.toFloat() / maxCount * 120).coerceAtLeast(4f)
            } else {
                4f // Flat placeholder bar for days without data.
            }
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.stats_bar_width),
                (barHeightDp * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@renderStatsGraph, R.drawable.stats_bar_bg)
            backgroundTintList = ColorStateList.valueOf(
                if (isToday) ContextCompat.getColor(this@renderStatsGraph, R.color.slp_blue)
                else ContextCompat.getColor(this@renderStatsGraph, R.color.button_secondary_border)
            )
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = dayLabel
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@renderStatsGraph, R.color.kitchen_text_secondary))
        }

        barWrapper.addView(bar)
        barWrapper.addView(label)
        binding.statsGraphContainer.addView(barWrapper)
    }
}
