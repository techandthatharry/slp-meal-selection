package com.techandthat.slpmealselection.network

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import java.util.concurrent.TimeUnit

// Encapsulates callable Cloud Function communication for Arbor operations.
class ArborSyncService(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("europe-west2")
) {
    // Calls syncTodaysMealChoices (single GraphQL call — no paging needed).
    fun syncMealChoices(
        schoolName: String,
        targetDate: String,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
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

    // Calls uploadArborBillingQueue and returns upload/delete outcome details.
    fun uploadBillingQueue(
        schoolName: String,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
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
