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

    val hasCheckInStarted = activeOrder != null || simulatedDatabase.any { it.served }
    val shouldHideLoadAndStart = serviceStarted
    val outstandingMeals = simulatedDatabase.filterNot { it.served }
    val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
    val hasPrepData = outstandingMeals.isNotEmpty()

    binding.headerTitle.text = when {
        serviceStarted && hasCheckInStarted -> getString(R.string.header_service_ongoing)
        serviceStarted -> getString(R.string.header_service_ongoing)
        showingServiceStats -> getString(R.string.header_summary_dashboard)
        else -> getString(R.string.header_prep_dashboard)
    }
    binding.headerLogo.visibility = View.VISIBLE
    binding.bloemfonteinLogo.visibility = View.VISIBLE

    if (showingServiceStats) {
        renderServiceStatsView()
        return
    }

    binding.contentTitle.text = if (serviceStarted) "SERVICE IN PROGRESS" else getString(R.string.kitchen_dashboard_title)
    binding.contentSubtitle.visibility = View.GONE

    binding.kitchenContent.visibility = View.VISIBLE
    binding.serviceEndedContent.visibility = View.GONE
    binding.childContent.visibility = View.GONE

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

    // Show brief loading indicator while checking Firebase cache.
    if (isLoadingMeals) {
        binding.prepLoadingText.visibility = View.VISIBLE
        binding.prepLoadingProgress.visibility = View.VISIBLE
        binding.prepLoadingProgress.isIndeterminate = true
    } else {
        binding.prepLoadingText.visibility = View.GONE
        binding.prepLoadingProgress.visibility = View.GONE
    }

    binding.changeSchoolButton.visibility = View.VISIBLE
    binding.endServiceButton.visibility = View.VISIBLE
    binding.changeSchoolButton.isEnabled = !serviceStarted
    binding.endServiceButton.isEnabled = serviceStarted || simulatedDatabase.isNotEmpty() || activeOrder != null

    if (firebaseStatusMessage.isNullOrBlank() || isLoadingMeals) {
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
            binding.kitchenOrderDietaryRequirements.text = "DIETARY: $dietaryText"
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
        binding.serviceStatsLine3.text = "Meals loaded at: ${stats.mealsLoadedTimeLabel}"
        binding.serviceStatsLine4.text = "Prep time: ${stats.prepDurationMinutes} mins"
        // TODO: Render pie chart/volumes here
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

internal fun MainActivity.renderServiceStatsView() {
    binding.contentTitle.text = getString(R.string.todays_service_stats_title)
    binding.contentSubtitle.visibility = View.GONE
    binding.kitchenContent.visibility = View.GONE
    binding.childContent.visibility = View.GONE
    binding.serviceEndedContent.visibility = View.VISIBLE

    // Hide control buttons in stats view
    binding.endServiceButton.visibility = View.GONE
    binding.changeSchoolButton.visibility = View.GONE
    binding.pauseServiceHeaderButton.visibility = View.GONE

    val stats = latestServiceStats
    if (stats != null) {
        binding.statsTodayValue.text = stats.mealsServed.toString()
        
        // Original stats
        binding.statsWeekValue.text = stats.weekTotal.toString()
        binding.statsWeekLabel.text = getString(R.string.stats_week)
        
        binding.statsMonthValue.text = stats.monthTotal.toString()
        binding.statsMonthLabel.text = getString(R.string.stats_month)
        
        // New stats added to the new cards
        binding.statsLoadedAtValue.text = stats.mealsLoadedTimeLabel
        binding.statsPrepTimeValue.text = "${stats.prepDurationMinutes}m"
        
        val syncPercent = if (stats.studentsLoaded > 0) {
            (stats.arborUploaded * 100) / stats.studentsLoaded
        } else 100
        binding.statsArborValue.text = getString(R.string.percent_format, syncPercent)
        
        if (syncPercent < 100) {
            binding.statsArborValue.setTextColor(ContextCompat.getColor(this, R.color.kitchen_danger))
        } else {
            binding.statsArborValue.setTextColor(ContextCompat.getColor(this, R.color.kitchen_success))
        }

        // Render Meal Volumes (Simple Bar Chart instead of Pie Chart for better fit)
        renderMealVolumeBars(stats.mealVolumes)
    }

    renderStatsGraph()
    binding.appScrollView.post {
        binding.appScrollView.scrollTo(0, 0)
    }
}

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
            text = meal.take(5) // Shorten meal name
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

internal fun MainActivity.renderStatsGraph() {
    binding.statsGraphContainer.removeAllViews()
    // Using a more realistic spread of mock data
    val dailyCounts = listOf(42, 38, 45, 41, 39, 0, latestServiceStats?.mealsServed ?: 0)
    val maxCount = dailyCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val density = resources.displayMetrics.density

    dailyCounts.forEachIndexed { index, count ->
        val barWrapper = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }

        // Add value label above bar if count > 0
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
            // Max height is 120dp to leave room for labels
            val barHeightDp = (count.toFloat() / maxCount * 120).coerceAtLeast(4f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.stats_bar_width),
                (barHeightDp * density).toInt()
            ).apply {
                topMargin = (2 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            background = ContextCompat.getDrawable(this@renderStatsGraph, R.drawable.stats_bar_bg)
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
