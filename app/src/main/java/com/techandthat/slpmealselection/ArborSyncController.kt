package com.techandthat.slpmealselection

import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Extension functions managing meal data loading via Arbor GraphQL sync.
// Architecture: "Load Today's Meals" → one GraphQL call → writes to Firestore → app reads Firestore.

// Presses "Load Today's Meals":
//  1. Sets loading state.
//  2. Calls syncTodaysMealChoices (single GraphQL call to Arbor).
//  3. On success: reads the written records from Firestore and displays them.
//  4. On failure: shows error and clears loading state.
internal fun MainActivity.fetchStudentsFromArbor() {
    isLoadingMeals = true
    firebaseStatusMessage = getString(R.string.loading_todays_meals)
    renderKitchenView()
    runMealChoicesSync(silent = false)
}

// Returns today's date as YYYY-MM-DD in Europe/London timezone.
private fun todayLondonDate(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    sdf.timeZone = TimeZone.getTimeZone("Europe/London")
    return sdf.format(Date())
}

// Calls syncTodaysMealChoices (single GraphQL call) which upserts full childRecords.
// On success: loads the written records from Firestore and updates the UI.
// On failure: clears loading state and shows error.
internal fun MainActivity.runMealChoicesSync(silent: Boolean) {
    arborSyncService.syncMealChoices(
        schoolName = selectedSchool,
        targetDate = todayLondonDate(),
        onSuccess = { data ->
            val written = (data?.get("written") as? Number)?.toInt() ?: 0
            Log.d("MealChoicesSync", "Sync complete: written=$written for $selectedSchool")
            isLoadingMeals = false
            firebaseStatusMessage = null
            loadChildRecordsFromFirestore()
            renderKitchenView()
            if (!silent) {
                Toast.makeText(
                    this,
                    "Meals loaded: $written students",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onFailure = { error ->
            Log.e("MealChoicesSync", "Meal choices sync failed", error)
            isLoadingMeals = false
            firebaseStatusMessage = "Failed to load meals: ${error.message}"
            renderKitchenView()
            if (!silent) {
                Toast.makeText(
                    this,
                    "Failed to load meals from Arbor",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )
}
