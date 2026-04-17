package com.techandthat.slpmealselection

import android.widget.Toast
import com.techandthat.slpmealselection.model.MealEntry

/**
 * Controller extension for MainActivity that manages high-level data synchronization
 * between the local in-memory state and the remote Firestore backend.
 */

// Ensures the user has a valid Firebase session before attempting to load student roster data.
internal fun MainActivity.authenticateThenLoadRoster() {
    // If already signed in, proceed directly to data loading.
    if (authManager.currentUserExists()) {
        loadChildRecordsFromFirestore()
        return
    }

    // Attempt anonymous sign-in to satisfy Firestore security rules.
    authManager.signInAnonymously(
        onSuccess = {
            loadChildRecordsFromFirestore()
        },
        onFailure = { error ->
            val failureMessage = getString(
                R.string.firebase_auth_failed_with_reason,
                error.message ?: "unknown"
            )
            isLoadingMeals = false
            firebaseStatusMessage = failureMessage
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
            renderAppContent()
            renderKitchenView()
        }
    )
}

// Fetches today's student records from Firestore and maps them to local model objects.
internal fun MainActivity.loadChildRecordsFromFirestore() {
    repository.loadRecords(
        schoolName = selectedSchool,
        onSuccess = { records ->
            // Handle the case where no records exist for the current school/day.
            if (records.isEmpty()) {
                simulatedDatabase.clear()
                renderAppContent()
                return@loadRecords
            }

            // Map Firestore DTOs to local UI-facing MealEntry models.
            val mapped = records.map { record ->
                val meal = record.mealSelected
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackMealByChild["${record.childName}|${record.className}"]
                    ?: "Meal not selected"
                MealEntry(
                    name = record.childName,
                    clazz = record.className,
                    meal = meal,
                    documentId = record.documentId,
                    schoolName = record.schoolName,
                    dietaryRequirements = record.dietaryRequirements,
                    served = record.served
                )
            }

            // Update the master list and refresh the UI.
            simulatedDatabase.clear()
            simulatedDatabase.addAll(mapped)
            renderAppContent()
        },
        onFailure = { error ->
            // Display loading failure to the user.
            val failureMessage = getString(
                R.string.firebase_load_failed_with_reason,
                error.message ?: "unknown"
            )
            firebaseStatusMessage = failureMessage
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
            renderAppContent()
            renderKitchenView()
        }
    )
}

// Updates the "served" status of a specific meal entry in the local list for zero-latency UI updates.
internal fun MainActivity.setMealServedLocally(target: MealEntry, served: Boolean) {
    // Identify the matching record in the in-memory list using ID or composite key.
    val index = simulatedDatabase.indexOfFirst { entry ->
        if (!target.documentId.isNullOrBlank() && !entry.documentId.isNullOrBlank()) {
            entry.documentId == target.documentId
        } else {
            entry.name == target.name && entry.clazz == target.clazz && entry.meal == target.meal
        }
    }

    // Apply the update locally.
    if (index >= 0) {
        simulatedDatabase[index].served = served
    } else {
        target.served = served
    }
}
