package com.techandthat.slpmealselection.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.R
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.MealEntry

/**
 * UI components for the "Child-Facing Tablet" mode.
 * Dynamically builds the step-by-step selection screens for students using a
 * button-based navigation flow optimized for large touchscreens.
 */

// Dynamically populates the "Class Selection" screen with buttons for each registration group.
fun renderClassSelectionStep(
    context: Context,
    binding: ActivityMainBinding,
    simulatedDatabase: List<MealEntry>,
    showWaitingOverlayAfterConfirm: Boolean,
    onClassSelected: (String) -> Unit
) {
    // Toggle container visibilities to show the class selection step.
    binding.classSelectionContainer.visibility = android.view.View.VISIBLE
    binding.nameSelectionContainer.visibility = android.view.View.GONE
    binding.checkInSuccessContainer.visibility = android.view.View.GONE

    // Ensure nested scrolling is enabled for the dynamic list.
    androidx.core.view.ViewCompat.setNestedScrollingEnabled(binding.classSelectionContainer, true)

    // Filter the master list for unique classes that have at least one unserved student.
    val classes = simulatedDatabase.filterNot { it.served }.map { it.clazz }.distinct().sorted()

    // Calculate dynamic button heights to fill the screen appropriately.
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.classSelectionContainer.measuredHeight.takeIf { it > 0 }

    // If layout hasn't completed yet, defer rendering until the next frame.
    if (containerHeight == null) {
        binding.classSelectionContainer.post {
            renderClassSelectionStep(
                context = context,
                binding = binding,
                simulatedDatabase = simulatedDatabase,
                showWaitingOverlayAfterConfirm = showWaitingOverlayAfterConfirm,
                onClassSelected = onClassSelected
            )
        }
        return
    }

    // Clear existing buttons and rebuild the list.
    binding.classButtonsContainer.removeAllViews()
    val classButtonHeight = containerHeight / 4

    // Apply branding and styling to the generated buttons.
    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)
    val density = context.resources.displayMetrics.density
    val cornerRadiusPx = (6 * density).toInt()
    val primaryColor = ContextCompat.getColor(context, R.color.kitchen_success)
    val primaryTextColor = ContextCompat.getColor(context, R.color.white)

    // Add a button for each unique class found.
    classes.forEach { className ->
        val button = MaterialButton(context).apply {
            text = className
            textSize = 34f
            isAllCaps = false
            typeface = gothamTypeface
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(primaryTextColor)
            setPadding(24, 24, 24, 24)
            cornerRadius = cornerRadiusPx
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                classButtonHeight
            ).apply {
                topMargin = 12
            }
            setOnClickListener {
                // Prevent multiple clicks if an overlay is about to show.
                if (!showWaitingOverlayAfterConfirm) {
                    onClassSelected(className)
                }
            }
        }
        binding.classButtonsContainer.addView(button)
    }
}

// Dynamically populates the "Name Selection" screen with buttons for students in the selected class.
fun renderNameSelectionStep(
    context: Context,
    binding: ActivityMainBinding,
    simulatedDatabase: List<MealEntry>,
    selectedClass: String?,
    activeOrder: MealEntry?,
    showWaitingOverlayAfterConfirm: Boolean,
    onMissingClass: () -> Unit,
    onChildSelected: (MealEntry) -> Unit
) {
    // Toggle container visibilities to show the name selection step.
    binding.classSelectionContainer.visibility = android.view.View.GONE
    binding.nameSelectionContainer.visibility = android.view.View.VISIBLE
    binding.checkInSuccessContainer.visibility = android.view.View.GONE
    binding.backToClassesButton.visibility = android.view.View.VISIBLE

    // Ensure scrolling is enabled for the dynamic name list.
    androidx.core.view.ViewCompat.setNestedScrollingEnabled(binding.nameListScroll, true)

    binding.nameButtonsContainer.removeAllViews()

    // Safety check: if no class is selected, revert to the previous step.
    if (selectedClass.isNullOrBlank()) {
        onMissingClass()
        return
    }

    // Filter to find unserved students within the selected class.
    val children = simulatedDatabase.filter { !it.served && it.clazz == selectedClass }

    // Calculate dynamic heights for touch-friendly buttons.
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.nameSelectionContainer.measuredHeight.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val childButtonHeight = (containerHeight / 6).coerceAtMost(180)

    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)
    val density = context.resources.displayMetrics.density
    val cornerRadiusPx = (6 * density).toInt()
    val primaryColor = ContextCompat.getColor(context, R.color.kitchen_success)
    val primaryTextColor = ContextCompat.getColor(context, R.color.white)

    // Add a button for each student, displaying their name and chosen meal if available.
    children.forEach { child ->
        val mealText = child.meal.takeIf {
            it.isNotBlank() && it != "Not selected"
        }
        val icon = if (mealText != null) mealIconFor(mealText) else ""
        val label = if (mealText != null) "${child.name}  $icon $mealText" else child.name

        val button = MaterialButton(context).apply {
            text = label
            textSize = 30f
            isAllCaps = false
            typeface = gothamTypeface
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(primaryTextColor)
            setPadding(24, 24, 24, 24)
            cornerRadius = cornerRadiusPx
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                childButtonHeight
            ).apply {
                topMargin = 12
            }
            setOnClickListener {
                // Trigger the "Active Order" state for the kitchen.
                if (activeOrder == null && !showWaitingOverlayAfterConfirm) {
                    onChildSelected(child)
                }
            }
        }
        binding.nameButtonsContainer.addView(button)
    }
}

// Maps meal names to appropriate emojis to aid visual recognition for younger students.
private fun mealIconFor(mealName: String): String {
    val normalized = mealName.lowercase()
    return when {
        normalized.contains("pizza") -> "🍕"
        normalized.contains("pasta") || normalized.contains("spaghetti") || normalized.contains("mac") -> "🍝"
        normalized.contains("fish") -> "🐟"
        normalized.contains("chicken") -> "🍗"
        normalized.contains("wrap") -> "🌯"
        normalized.contains("potato") || normalized.contains("jacket") -> "🥔"
        normalized.contains("curry") -> "🍛"
        normalized.contains("salad") -> "🥗"
        normalized.contains("burger") -> "🍔"
        else -> "🍽️"
    }
}

// Renders the final "Selection Success" screen with a confirmation of the chosen meal.
fun renderSuccessStep(binding: ActivityMainBinding, activeOrder: MealEntry?) {
    binding.classSelectionContainer.visibility = android.view.View.GONE
    binding.nameSelectionContainer.visibility = android.view.View.GONE
    binding.checkInSuccessContainer.visibility = android.view.View.VISIBLE

    // Display the meal name and icon on the final "Confirm" button.
    val mealLabel = activeOrder?.meal?.takeIf { it.isNotBlank() } ?: "Meal"
    val icon = mealIconFor(mealLabel)
    binding.checkInSuccessButton.text =
        binding.root.context.getString(
            R.string.child_success_button_meal,
            icon,
            mealLabel
        )
}
