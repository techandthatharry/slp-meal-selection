package com.techandthat.slpmealselection.ui

import android.content.Context
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import com.techandthat.slpmealselection.model.MealEntry

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

    val classes = simulatedDatabase.filterNot { it.served }.map { it.clazz }.distinct().sorted()

    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.classSelectionContainer.measuredHeight.takeIf { it > 0 }

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

    classes.forEach { className ->
        val button = MaterialButton(context).apply {
            text = className
            textSize = 34f
            isAllCaps = false
            setPadding(24, 24, 24, 24)
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

    if (selectedClass.isNullOrBlank()) {
        onMissingClass()
        return
    }

    val children = simulatedDatabase.filter { !it.served && it.clazz == selectedClass }
    val containerHeight = binding.childStepContainer.measuredHeight.takeIf { it > 0 }
        ?: binding.childStepContainer.height.takeIf { it > 0 }
        ?: binding.nameSelectionContainer.measuredHeight.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val childButtonHeight = containerHeight / 5

    children.forEach { child ->
        val button = MaterialButton(context).apply {
            text = child.name
            textSize = 34f
            isAllCaps = false
            setPadding(24, 24, 24, 24)
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

fun renderSuccessStep(binding: ActivityMainBinding) {
    binding.classSelectionContainer.visibility = android.view.View.GONE
    binding.nameSelectionContainer.visibility = android.view.View.GONE
    binding.checkInSuccessContainer.visibility = android.view.View.VISIBLE
}
