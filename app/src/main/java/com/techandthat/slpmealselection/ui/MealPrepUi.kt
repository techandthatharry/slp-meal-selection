package com.techandthat.slpmealselection.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
            val emptyState = TextView(context).apply {
                text = context.getString(R.string.no_meals_loaded)
                setTextColor(ContextCompat.getColor(context, R.color.meal_count_text))
                textSize = 20f
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
                val iconView = row.findViewById<ImageView>(R.id.mealTypeIcon)
                val countView = row.findViewById<TextView>(R.id.mealCountText)
                val nameView = row.findViewById<TextView>(R.id.mealNameText)

                iconView.setImageResource(mealIconFor(mealName))
                countView.text = context.getString(R.string.meal_count_format, count)
                nameView.text = mealName
                binding.prepSummaryContainer.addView(row)
            }
    }

    // Returns an icon resource based on meal name keywords.
    private fun mealIconFor(mealName: String): Int {
        val normalized = mealName.lowercase(Locale.getDefault())
        return when {
            "wrap" in normalized -> android.R.drawable.ic_menu_upload
            "fish" in normalized -> android.R.drawable.ic_menu_gallery
            "potato" in normalized -> android.R.drawable.ic_menu_my_calendar
            "pasta" in normalized -> android.R.drawable.ic_menu_sort_by_size
            "curry" in normalized -> android.R.drawable.ic_menu_compass
            else -> android.R.drawable.ic_menu_info_details
        }
    }
}
