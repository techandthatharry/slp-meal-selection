package com.techandthat.slpmealselection.data

import com.google.firebase.firestore.FirebaseFirestore
import com.techandthat.slpmealselection.model.MealEntry

class ChildRecordsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val childRecordsCollection = firestore.collection("childRecords")

    data class ChildRecord(
        val childName: String,
        val className: String
    )

    fun loadRecords(
        onSuccess: (List<ChildRecord>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection.get()
            .addOnSuccessListener { snapshot ->
                val records = snapshot.documents.mapNotNull { doc ->
                    val childName = doc.getString("childName")
                    val className = doc.getString("className")
                    if (childName.isNullOrBlank() || className.isNullOrBlank()) return@mapNotNull null
                    ChildRecord(childName, className)
                }
                onSuccess(records)
            }
            .addOnFailureListener(onFailure)
    }

    fun seedInitialData(
        initialDummyData: List<MealEntry>,
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val batch = firestore.batch()
        initialDummyData.forEach { entry ->
            val record = mapOf(
                "childName" to entry.name,
                "className" to entry.clazz,
                "schoolName" to schoolName
            )
            val documentId = "${entry.clazz}_${entry.name}".replace(" ", "_")
            batch.set(childRecordsCollection.document(documentId), record)
        }

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun deleteAllRecords(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection.get()
            .addOnSuccessListener { snapshot ->
                val batch = firestore.batch()
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }
}
