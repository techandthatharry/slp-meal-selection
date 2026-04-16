package com.techandthat.slpmealselection

import android.widget.Toast
import com.techandthat.slpmealselection.model.MealEntry

// Extension functions managing Firestore data loading, auth bootstrapping, and local state updates.

// Ensures user is authenticated before reading roster data from Firestore.
internal fun MainActivity.authenticateThenLoadRoster() {
    if (authManager.currentUserExists()) {
        loadChildRecordsFromFirestore()
        return
    }

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

// Loads Firestore child records into in-memory meal list used by both tablet modes.
internal fun MainActivity.loadChildRecordsFromFirestore() {
    repository.loadRecords(
        schoolName = selectedSchool,
        onSuccess = { records ->
            if (records.isEmpty()) {
                simulatedDatabase.clear()
                renderAppContent()
                return@loadRecords
            }

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

            simulatedDatabase.clear()
            simulatedDatabase.addAll(mapped)
            renderAppContent()
        },
        onFailure = { error ->
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

// Updates in-memory meal entries by document ID or name+class for immediate UI refreshes.
internal fun MainActivity.setMealServedLocally(target: MealEntry, served: Boolean) {
    val index = simulatedDatabase.indexOfFirst { entry ->
        if (!target.documentId.isNullOrBlank() && !entry.documentId.isNullOrBlank()) {
            entry.documentId == target.documentId
        } else {
            entry.name == target.name && entry.clazz == target.clazz && entry.meal == target.meal
        }
    }

    if (index >= 0) {
        simulatedDatabase[index].served = served
    } else {
        target.served = served
    }
}
