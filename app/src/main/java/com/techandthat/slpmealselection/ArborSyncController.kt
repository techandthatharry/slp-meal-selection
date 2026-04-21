package com.techandthat.slpmealselection

import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Extension functions for MainActivity that handle importing meal data from Arbor MIS.
 * The workflow involves triggering a Firebase Cloud Function that performs a GraphQL
 * query against Arbor and populates the Firestore database for the current day.
 */

// Initiates the process of fetching today's meal choices from the Arbor MIS.
internal fun MainActivity.fetchStudentsFromArbor() {
    isLoadingMeals = true
    firebaseStatusMessage = getString(R.string.loading_todays_meals)
    
    // Update the UI to reflect the loading state.
    renderKitchenView()
    
    // Execute the cloud sync function.
    runMealChoicesSync(silent = false)
}

// Generates a YYYY-MM-DD date string formatted for the London timezone, as required by Arbor.
private fun todayLondonDate(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    sdf.timeZone = TimeZone.getTimeZone("Europe/London")
    return sdf.format(Date())
}

// Executes the HttpsCallable to synchronize meal data from Arbor to Firestore.
internal fun MainActivity.runMealChoicesSync(silent: Boolean) {
    arborSyncService.syncMealChoices(
        schoolName = selectedSchool,
        targetDate = todayLondonDate(),
        onSuccess = { data ->
            // Parse response metadata from the synchronization function.
            val success = data?.get("success") as? Boolean ?: true
            val written = (data?.get("written") as? Number)?.toInt() ?: 0
            val serverMsg = data?.get("message") as? String ?: ""
            
            Log.d(
                "MealChoicesSync",
                "Sync result: success=$success written=$written school=$selectedSchool msg=$serverMsg"
            )
            
            isLoadingMeals = false
            
            if (success && written > 0) {
                // Success: Records were written to Firestore, now fetch them for the local UI.
                firebaseStatusMessage = null
                mealsLoadedTime = System.currentTimeMillis()
                loadChildRecordsFromFirestore()
                renderKitchenView()
                if (!silent) {
                    Toast.makeText(this, "Meals loaded: $written students", Toast.LENGTH_SHORT)
                        .show()
                }
            } else if (success && written == 0) {
                // Success but empty: No records found for the target date.
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
                // Backend logic error: The function ran but encountered an API/Logic issue.
                firebaseStatusMessage = "Sync error: $serverMsg"
                renderKitchenView()
                if (!silent) {
                    Toast.makeText(this, "Sync error: $serverMsg", Toast.LENGTH_LONG).show()
                }
            }
        },
        onFailure = { error ->
            // Infrastructure error: Network failure or Cloud Function exception.
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
