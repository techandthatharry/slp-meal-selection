package com.techandthat.slpmealselection.network

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult

class ArborSyncService(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("europe-west2")
) {
    fun syncStudents(
        schoolName: String,
        maxRecords: Int,
        onSuccess: (Map<String, Any>?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        functions
            .getHttpsCallable("getArborStudents")
            .call(
                mapOf(
                    "schoolName" to schoolName,
                    "maxRecords" to maxRecords
                )
            )
            .addOnSuccessListener { result: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                onSuccess(result.data as? Map<String, Any>)
            }
            .addOnFailureListener(onFailure)
    }
}
