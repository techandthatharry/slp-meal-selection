package com.techandthat.slpmealselection.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.techandthat.slpmealselection.R
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import java.util.Locale

// Renders the Kitchen meal-prep summary list with counts and meal icons.
object MealPrepUi {
    // Draws meal rows from grouped meal counts or an empty-state message.
    fun renderMealPrepRows(
        context: Context,
        binding: ActivityMainBinding,
        volumes: Map<String, Int>
    ) {
        binding.prepSummaryContainer.removeAllViews()

        // Show empty placeholder when no prep data exists.
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

        // Render one row per meal type in alphabetical order.
        volumes.entries
            .sortedBy { it.key.lowercase(Locale.getDefault()) }
            .forEach { (mealName, count) ->
                val row = inflater.inflate(R.layout.item_meal_prep, binding.prepSummaryContainer, false)
                val iconView = row.findViewById<TextView>(R.id.mealTypeIcon)
                val countView = row.findViewById<TextView>(R.id.mealCountText)
                val nameView = row.findViewById<TextView>(R.id.mealNameText)

                iconView.text = mealIconFor(mealName)
                countView.text = context.getString(R.string.meal_count_format, count)
                nameView.text = mealName
                binding.prepSummaryContainer.addView(row)
            }
    }

    // Returns a meal-type emoji icon based on meal name keywords.
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
            else -> "🍽️"
        }
    }
}
