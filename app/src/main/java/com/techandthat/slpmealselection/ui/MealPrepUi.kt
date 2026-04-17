package com.techandthat.slpmealselection.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.techandthat.slpmealselection.R
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import java.util.Locale

/**
 * UI components for the "Kitchen-Facing Tablet" mode.
 * Handles the rendering of the meal preparation summary list, which aggregates
 * student meal choices into total counts for the kitchen staff.
 */
object MealPrepUi {
    // Populates the kitchen dashboard with rows showing the total count for each meal type.
    fun renderMealPrepRows(
        context: Context,
        binding: ActivityMainBinding,
        volumes: Map<String, Int>
    ) {
        // Reset the container before drawing new rows.
        binding.prepSummaryContainer.removeAllViews()

        // Display an empty state message if no meals have been loaded for the day.
        if (volumes.isEmpty()) {
            val gothamTypeface = ResourcesCompat.getFont(context, R.font.gotham)
            val emptyState = TextView(context).apply {
                text = context.getString(R.string.no_meals_loaded)
                setTextColor(ContextCompat.getColor(context, R.color.meal_count_text))
                textSize = 20f
                typeface = gothamTypeface
            }
            binding.prepSummaryContainer.addView(emptyState)
            return
        }

        val inflater = LayoutInflater.from(context)

        // Sort meal names alphabetically and create a summary row for each.
        volumes.entries
            .sortedBy { it.key.lowercase(Locale.getDefault()) }
            .forEach { (mealName, count) ->
                // Inflate the custom row layout.
                val row = inflater.inflate(R.layout.item_meal_prep, binding.prepSummaryContainer, false)
                val iconView = row.findViewById<TextView>(R.id.mealTypeIcon)
                val countView = row.findViewById<TextView>(R.id.mealCountText)
                val nameView = row.findViewById<TextView>(R.id.mealNameText)

                // Assign the appropriate icon and total volume to the row.
                iconView.text = mealIconFor(mealName)
                countView.text = context.getString(R.string.meal_count_format, count)
                nameView.text = mealName
                
                // Add the completed row to the dashboard.
                binding.prepSummaryContainer.addView(row)
            }
    }

    // Identifies a representative emoji icon for the given meal name based on keyword matching.
    private fun mealIconFor(mealName: String): String {
        val normalized = mealName.lowercase(Locale.getDefault())
        return when {
            "pizza" in normalized -> "🍕"
            "pasta" in normalized || "spaghetti" in normalized || "mac" in normalized -> "🍝"
            "chicken" in normalized || "drumstick" in normalized || "nugget" in normalized -> "🍗"
            "fish" in normalized || "chips" in normalized -> "🐟"
            "curry" in normalized || "rice" in normalized -> "🍛"
            "potato" in normalized || "jacket" in normalized || "fries" in normalized -> "🥔"
            "wrap" in normalized || "sandwich" in normalized -> "🌯"
            "salad" in normalized -> "🥗"
            "burger" in normalized -> "🍔"
            else -> "🍽️" // Default icon for unrecognized meals.
        }
    }
}
