package com.techandthat.slpmealselection.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.techandthat.slpmealselection.model.MealEntry

// Handles all Firestore CRUD operations for child meal records.
class ChildRecordsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val childRecordsCollection = firestore.collection("childRecords")

    // Lightweight record shape used when loading existing records.
    data class ChildRecord(
        val documentId: String,
        val childName: String,
        val className: String,
        val schoolName: String?,
        val mealSelected: String?,
        val dietaryRequirements: List<String>,
        val served: Boolean
    )

    // Checks whether any childRecords exist for the given school (fast limit-1 query).
    fun hasRecordsForSchool(
        schoolName: String,
        onResult: (Boolean) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection
            .whereEqualTo("schoolName", schoolName)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot -> onResult(!snapshot.isEmpty) }
            .addOnFailureListener(onFailure)
    }

    // Reads childRecords from Firestore filtered to the given school.
    fun loadRecords(
        schoolName: String,
        onSuccess: (List<ChildRecord>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection
            .whereEqualTo("schoolName", schoolName)
            .get()
            .addOnSuccessListener { snapshot ->
                // Convert Firestore docs to domain rows and skip malformed entries.
                val records = snapshot.documents.mapNotNull { doc ->
                    val childName = doc.getString("childName")
                    val className = doc.getString("className")
                    val schoolName = doc.getString("schoolName")
                    val mealSelected = doc.getString("mealSelected")
                    @Suppress("UNCHECKED_CAST")
                    val dietaryRequirements = (doc.get("dietaryRequirements") as? List<Any>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList()
                    val served = doc.getBoolean("served") ?: false
                    if (childName.isNullOrBlank() || className.isNullOrBlank()) return@mapNotNull null
                    ChildRecord(doc.id, childName, className, schoolName, mealSelected, dietaryRequirements, served)
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

    // Deletes all childRecords belonging to the given school only.
    fun deleteAllRecords(
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection
            .whereEqualTo("schoolName", schoolName)
            .get()
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
                    "dietaryRequirements" to record.dietaryRequirements,
                    "served" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
        }

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Persists a served meal event and queues billing payload for Arbor handoff.
    fun markMealServed(
        entry: MealEntry,
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val docId = entry.documentId
            ?: "${entry.clazz}_${entry.name}".replace(" ", "_")
        val recordRef = childRecordsCollection.document(docId)
        val billingRef = firestore.collection("arborBillingQueue").document()

        val batch = firestore.batch()

        // Mark the source child record as served for prep/billing consistency.
        batch.set(
            recordRef,
            mapOf(
                "served" to true,
                "servedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )

        // Enqueue a billing payload that can be processed and sent back to Arbor.
        batch.set(
            billingRef,
            mapOf(
                "schoolName" to (entry.schoolName ?: schoolName),
                "childName" to entry.name,
                "className" to entry.clazz,
                "meal" to entry.meal,
                "status" to "pending",
                "sourceDocumentId" to docId,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
