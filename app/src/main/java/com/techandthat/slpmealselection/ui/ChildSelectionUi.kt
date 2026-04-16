package com.techandthat.slpmealselection.ui

import android.content.Context
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.R
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.MealEntry

// Renders child-tablet class/name/success selection steps.

// Builds class selection buttons from outstanding meal records.
fun renderClassSelectionStep(
    context: Context,
    binding: ActivityMainBinding,
    simulatedDatabase: List<MealEntry>,
    showWaitingOverlayAfterConfirm: Boolean,
    onClassSelected: (String) -> Unit
) {
    binding.classSelectionContainer.visibility = android.view.View.VISIBLE
    binding.nameSelectionContainer.visibility = android.view.View.GONE
    binding.checkInSuccessContainer.visibility = android.view.View.GONE

    // Extract unique classes that still have unserved meals.
    val classes = simulatedDatabase.filterNot { it.served }.map { it.clazz }.distinct().sorted()

    // Resolve visible container height to size generated buttons.
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.classSelectionContainer.measuredHeight.takeIf { it > 0 }

    // Retry after layout pass if height is not available yet.
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

    binding.classButtonsContainer.removeAllViews()
    val classButtonHeight = containerHeight / 4

    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)

    // Create one full-width button per class.
    classes.forEach { className ->
        val button = MaterialButton(context).apply {
            text = className
            textSize = 34f
            isAllCaps = false
            typeface = gothamTypeface
            setPadding(24, 24, 24, 24)
            cornerRadius = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                classButtonHeight
            ).apply {
                topMargin = 12
            }
            setOnClickListener {
                if (!showWaitingOverlayAfterConfirm) {
                    onClassSelected(className)
                }
            }
        }
        binding.classButtonsContainer.addView(button)
    }
}

// Builds name selection buttons for children in the selected class.
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
    binding.classSelectionContainer.visibility = android.view.View.GONE
    binding.nameSelectionContainer.visibility = android.view.View.VISIBLE
    binding.checkInSuccessContainer.visibility = android.view.View.GONE
    binding.backToClassesButton.visibility = android.view.View.VISIBLE

    binding.nameButtonsContainer.removeAllViews()

    // If class context is missing, return to class step.
    if (selectedClass.isNullOrBlank()) {
        onMissingClass()
        return
    }

    // Filter to children who still need meal check-in.
    val children = simulatedDatabase.filter { !it.served && it.clazz == selectedClass }

    // Resolve available height for dynamic name button sizing.
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.nameSelectionContainer.measuredHeight.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val childButtonHeight = (containerHeight / 6).coerceAtMost(180)

    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)

    // Create one full-width button per child.
    children.forEach { child ->
        val button = MaterialButton(context).apply {
            text = child.name
            textSize = 34f
            isAllCaps = false
            typeface = gothamTypeface
            setPadding(24, 24, 24, 24)
            cornerRadius = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                childButtonHeight
            ).apply {
                topMargin = 12
            }
            setOnClickListener {
                if (activeOrder == null && !showWaitingOverlayAfterConfirm) {
                    onChildSelected(child)
                }
            }
        }
        binding.nameButtonsContainer.addView(button)
    }
}

// Resolves a meal icon based on meal name keywords.
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

// Shows final success state after a child check-in is confirmed.
fun renderSuccessStep(binding: ActivityMainBinding, activeOrder: MealEntry?) {
    binding.classSelectionContainer.visibility = android.view.View.GONE
    binding.nameSelectionContainer.visibility = android.view.View.GONE
    binding.checkInSuccessContainer.visibility = android.view.View.VISIBLE

    // Show expected meal and matching icon on the large green confirmation button.
    val mealLabel = activeOrder?.meal?.takeIf { it.isNotBlank() } ?: "Meal"
    val icon = mealIconFor(mealLabel)
    binding.checkInSuccessButton.text =
        binding.root.context.getString(
            R.string.child_success_button_meal,
            icon,
            mealLabel
        )
}
