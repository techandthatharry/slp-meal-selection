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
            val success = data?.get("success") as? Boolean ?: true
            val written = (data?.get("written") as? Number)?.toInt() ?: 0
            val serverMsg = data?.get("message") as? String ?: ""
            Log.d(
                "MealChoicesSync",
                "Sync result: success=$success written=$written school=$selectedSchool msg=$serverMsg"
            )
            isLoadingMeals = false
            if (success && written > 0) {
                firebaseStatusMessage = null
                mealsLoadedTime = System.currentTimeMillis()
                loadChildRecordsFromFirestore()
                renderKitchenView()
                if (!silent) {
                    Toast.makeText(this, "Meals loaded: $written students", Toast.LENGTH_SHORT)
                        .show()
                }
            } else if (success && written == 0) {
                firebaseStatusMessage = "No meal selections found in Arbor for today"
                renderKitchenView()
                if (!silent) {
                    Toast.makeText(
                        this,
                        "No meal selections found for today in Arbor",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // Callable returned success=false with an error message from the server.
                firebaseStatusMessage = "Sync error: $serverMsg"
                renderKitchenView()
                if (!silent) {
                    Toast.makeText(this, "Sync error: $serverMsg", Toast.LENGTH_LONG).show()
                }
            }
        },
        onFailure = { error ->
            Log.e("MealChoicesSync", "Meal choices sync failed", error)
            repository.logErrorToFirebase("MealChoicesSync", error, selectedSchool)
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
