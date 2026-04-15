package com.techandthat.slpmealselection.data

import com.google.firebase.firestore.FirebaseFirestore
import com.techandthat.slpmealselection.model.MealEntry

// Handles all Firestore CRUD operations for child meal records.
class ChildRecordsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val childRecordsCollection = firestore.collection("childRecords")

    // Lightweight record shape used when loading existing records.
    data class ChildRecord(
        val childName: String,
        val className: String,
        val mealSelected: String?
    )

    // Reads childRecords from Firestore and maps them into ChildRecord models.
    fun loadRecords(
        onSuccess: (List<ChildRecord>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection.get()
            .addOnSuccessListener { snapshot ->
                // Convert Firestore docs to domain rows and skip malformed entries.
                val records = snapshot.documents.mapNotNull { doc ->
                    val childName = doc.getString("childName")
                    val className = doc.getString("className")
                    val mealSelected = doc.getString("mealSelected")
                    if (childName.isNullOrBlank() || className.isNullOrBlank()) return@mapNotNull null
                    ChildRecord(childName, className, mealSelected)
                }
                onSuccess(records)
            }
            .addOnFailureListener(onFailure)
    }

    // Seeds initial static data into Firestore for bootstrap/demo scenarios.
    fun seedInitialData(
        initialDummyData: List<MealEntry>,
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val batch = firestore.batch()

        // Create/merge one Firestore document per dummy record.
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

    // Deletes every document in childRecords collection.
    fun deleteAllRecords(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection.get()
            .addOnSuccessListener { snapshot ->
                val batch = firestore.batch()

                // Queue delete operation for each existing record.
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }

                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }

    // Full record shape used when saving Arbor sync results.
    data class ArborStudentRecord(
        val documentId: String,
        val childName: String,
        val className: String,
        val schoolName: String,
        val source: String,
        val mealSelected: String,
        val dietaryRequirements: List<String>
    )

    // Upserts Arbor records into Firestore in a single batch transaction.
    fun upsertArborRecords(
        records: List<ArborStudentRecord>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Short-circuit when there is nothing to write.
        if (records.isEmpty()) {
            onSuccess()
            return
        }

        val batch = firestore.batch()

        // Queue create/update operation for every Arbor record.
        records.forEach { record ->
            batch.set(
                childRecordsCollection.document(record.documentId),
                mapOf(
                    "childName" to record.childName,
                    "className" to record.className,
                    "schoolName" to record.schoolName,
                    "source" to record.source,
                    "mealSelected" to record.mealSelected,
                    "dietaryRequirements" to record.dietaryRequirements
                )
            )
        }

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
