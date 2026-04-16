package com.techandthat.slpmealselection.network

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import java.util.concurrent.TimeUnit

// Encapsulates callable Cloud Function communication for Arbor sync operations.
class ArborSyncService(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("europe-west2")
) {
    // Calls getArborStudents and returns the decoded payload to the caller.
    fun syncStudents(
        schoolName: String,
        maxRecords: Int,
        offset: Int,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        functions
            .getHttpsCallable("getArborStudents")
            .apply { setTimeout(90, TimeUnit.SECONDS) }
            .call(
                mapOf(
                    "schoolName" to schoolName,
                    "maxRecords" to maxRecords,
                    "offset" to offset
                )
            )
            .addOnSuccessListener { result: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                onSuccess(result.data as? Map<String, Any>)
            }
            .addOnFailureListener(onFailure)
    }

    // Calls buildArborClassMap to pre-build studentId → className in Firestore.
    // Supports batched form processing via formOffset.
    fun buildClassMap(
        schoolName: String,
        formOffset: Int,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        functions
            .getHttpsCallable("buildArborClassMap")
            .apply { setTimeout(90, TimeUnit.SECONDS) }
            .call(mapOf("schoolName" to schoolName, "formOffset" to formOffset))
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
