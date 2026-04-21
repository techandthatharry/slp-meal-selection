package com.techandthat.slpmealselection.network

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import java.util.concurrent.TimeUnit

/**
 * Service responsible for communication with Firebase Cloud Functions.
 * These functions act as a secure bridge between the tablet app and the Arbor MIS.
 */
class ArborSyncService(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("europe-west2")
) {
    // Triggers the 'syncTodaysMealChoices' cloud function to fetch student selections for today.
    fun syncMealChoices(
        schoolName: String,
        targetDate: String,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Execute the GraphQL sync function with a 1-minute timeout.
        functions
            .getHttpsCallable("syncTodaysMealChoices")
            .apply { setTimeout(60, TimeUnit.SECONDS) }
            .call(mapOf("schoolName" to schoolName, "targetDate" to targetDate))
            .addOnSuccessListener { result: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                onSuccess(result.data as? Map<String, Any>)
            }
            .addOnFailureListener(onFailure)
    }

    // Triggers the 'uploadArborBillingQueue' cloud function to sync served meals back to Arbor.
    fun uploadBillingQueue(
        schoolName: String,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Call the billing upload function using the school context.
        functions
            .getHttpsCallable("uploadArborBillingQueue")
            .call(mapOf("schoolName" to schoolName))
            .addOnSuccessListener { result: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                onSuccess(result.data as? Map<String, Any>)
            }
            .addOnFailureListener(onFailure)
    }
}
