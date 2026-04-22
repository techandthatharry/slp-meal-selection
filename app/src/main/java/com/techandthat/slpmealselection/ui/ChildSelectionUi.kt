package com.techandthat.slpmealselection.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.R
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.MealEntry

/**
 * UI components for the "Student-Facing Tablet" mode.
 * Dynamically builds the step-by-step selection screens using a kiosk-friendly
 * list design: white background, black text, and grey row separators.
 */

// Light grey used for row separators throughout the selection screens.
private const val SEPARATOR_COLOR = "#E0E0E0"
// Row text color for selection buttons.
private const val ROW_TEXT_COLOR = "#212121"

// Dynamically populates the "Class Selection" screen with buttons for each registration group.
fun renderClassSelectionStep(
    context: Context,
    binding: ActivityMainBinding,
    simulatedDatabase: List<MealEntry>,
    showWaitingOverlayAfterConfirm: Boolean,
    onClassSelected: (String) -> Unit
) {
    // Toggle container visibilities to show the class selection step.
    binding.classSelectionContainer.visibility = View.VISIBLE
    binding.nameSelectionContainer.visibility = View.GONE
    binding.checkInSuccessContainer.visibility = View.GONE

    // White backgrounds for kiosk-friendly appearance.
    binding.classSelectionContainer.setBackgroundColor(Color.WHITE)
    binding.classButtonsContainer.setBackgroundColor(Color.WHITE)

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

    // Aim for ~4 classes per screen; clamp to a sensible touch target range.
    val classButtonHeight = (containerHeight / 4).coerceIn(80, 200)

    // Shared styling values.
    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)
    val density = context.resources.displayMetrics.density
    val separatorHeightPx = (1 * density).toInt().coerceAtLeast(1)
    val textColor = Color.parseColor(ROW_TEXT_COLOR)
    val strokeColor = ColorStateList.valueOf(Color.parseColor(SEPARATOR_COLOR))

    // Add a button for each unique class found.
    classes.forEachIndexed { index, className ->
        val button = MaterialButton(context).apply {
            text = className
            textSize = 32f
            isAllCaps = false
            typeface = gothamTypeface
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(textColor)
            setPadding(32, 0, 32, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            cornerRadius = 0
            strokeWidth = 0
            insetTop = 0
            insetBottom = 0
            minHeight = classButtonHeight
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                classButtonHeight
            )
            setOnClickListener {
                // Prevent multiple clicks if an overlay is about to show.
                if (!showWaitingOverlayAfterConfirm) {
                    onClassSelected(className)
                }
            }
        }
        binding.classButtonsContainer.addView(button)

        // Add a grey separator after every row.
        val separator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                separatorHeightPx
            )
            setBackgroundColor(Color.parseColor(SEPARATOR_COLOR))
        }
        binding.classButtonsContainer.addView(separator)
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
    onStudentSelected: (MealEntry) -> Unit
) {
    // Toggle container visibilities to show the name selection step.
    binding.classSelectionContainer.visibility = View.GONE
    binding.nameSelectionContainer.visibility = View.VISIBLE
    binding.checkInSuccessContainer.visibility = View.GONE
    binding.backToClassesButton.visibility = View.VISIBLE

    // White backgrounds for kiosk-friendly appearance.
    binding.nameSelectionContainer.setBackgroundColor(Color.WHITE)
    binding.nameButtonsContainer.setBackgroundColor(Color.WHITE)

    // Ensure scrolling is enabled for the dynamic name list.
    androidx.core.view.ViewCompat.setNestedScrollingEnabled(binding.nameListScroll, true)

    binding.nameButtonsContainer.removeAllViews()

    // Safety check: if no class is selected, revert to the previous step.
    if (selectedClass.isNullOrBlank()) {
        onMissingClass()
        return
    }

    // Filter to find unserved students within the selected class.
    val students = simulatedDatabase.filter { !it.served && it.clazz == selectedClass }

    // Calculate dynamic heights for touch-friendly buttons.
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.nameSelectionContainer.measuredHeight.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val studentButtonHeight = (containerHeight / 6).coerceIn(72, 160)

    val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)
    val density = context.resources.displayMetrics.density
    val separatorHeightPx = (1 * density).toInt().coerceAtLeast(1)
    val textColor = Color.parseColor(ROW_TEXT_COLOR)

    // Add a button for each student, displaying their name and chosen meal if available.
    students.forEach { student ->
        val mealText = student.meal.takeIf {
            it.isNotBlank() && it != "Not selected"
        }
        val icon = if (mealText != null) mealIconFor(mealText) else ""
        val label = if (mealText != null) "${student.name}  $icon $mealText" else student.name

        val button = MaterialButton(context).apply {
            text = label
            textSize = 28f
            isAllCaps = false
            typeface = gothamTypeface
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(textColor)
            setPadding(32, 0, 32, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            cornerRadius = 0
            strokeWidth = 0
            insetTop = 0
            insetBottom = 0
            minHeight = studentButtonHeight
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                studentButtonHeight
            )
            setOnClickListener {
                if (activeOrder == null && !showWaitingOverlayAfterConfirm) {
                    onStudentSelected(student)
                }
            }
        }
        binding.nameButtonsContainer.addView(button)

        // Add a grey separator after every row.
        val separator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                separatorHeightPx
            )
            setBackgroundColor(Color.parseColor(SEPARATOR_COLOR))
        }
        binding.nameButtonsContainer.addView(separator)
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
    binding.classSelectionContainer.visibility = View.GONE
    binding.nameSelectionContainer.visibility = View.GONE
    binding.checkInSuccessContainer.visibility = View.VISIBLE

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
