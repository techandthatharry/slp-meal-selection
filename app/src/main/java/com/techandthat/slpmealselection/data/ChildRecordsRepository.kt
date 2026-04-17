package com.techandthat.slpmealselection.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.techandthat.slpmealselection.model.MealEntry

/**
 * Data Repository responsible for all Firestore CRUD operations.
 * Handles student rosters, meal selection persistence, billing queues, and error logging.
 */
class ChildRecordsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    // Reference to the primary collection for today's meal records.
    private val childRecordsCollection = firestore.collection("childRecords")

    // Lightweight record shape used for internal mapping when fetching data.
    data class ChildRecord(
        val documentId: String,
        val childName: String,
        val className: String,
        val schoolName: String?,
        val mealSelected: String?,
        val dietaryRequirements: List<String>,
        val served: Boolean
    )

    // Checks if a school already has records for today (used to skip redundant syncs).
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

    // Fetches all student/meal records for a specific school.
    fun loadRecords(
        schoolName: String,
        onSuccess: (List<ChildRecord>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        childRecordsCollection
            .whereEqualTo("schoolName", schoolName)
            .get()
            .addOnSuccessListener { snapshot ->
                // Map Firestore documents to local ChildRecord objects.
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
                    
                    // Filter out any corrupted or incomplete records.
                    if (childName.isNullOrBlank() || className.isNullOrBlank()) return@mapNotNull null
                    
                    ChildRecord(doc.id, childName, className, schoolName, mealSelected, dietaryRequirements, served)
                }
                onSuccess(records)
            }
            .addOnFailureListener(onFailure)
    }

    // Populates Firestore with initial dummy data for development and testing.
    fun seedInitialData(
        initialDummyData: List<MealEntry>,
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val batch = firestore.batch()

        // Batch upload each dummy entry.
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

    // Removes all student meal records for a school (typically called at end-of-day).
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

                // Queue all documents for deletion in a single batch.
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }

                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }

    // Detailed model used for synchronization from Arbor.
    data class ArborStudentRecord(
        val documentId: String,
        val childName: String,
        val className: String,
        val schoolName: String,
        val source: String,
        val mealSelected: String,
        val dietaryRequirements: List<String>
    )

    // Synchronizes records from the Arbor API into the local Firestore collection.
    fun upsertArborRecords(
        records: List<ArborStudentRecord>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (records.isEmpty()) {
            onSuccess()
            return
        }

        val batch = firestore.batch()

        // Use merge sets to update existing records or create new ones without overwriting 'served' flags.
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

    // Updates a student's status as served and queues a billing record for the Arbor sync.
    fun markMealServed(
        entry: MealEntry,
        schoolName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Generate a deterministic document ID.
        val docId = entry.documentId
            ?: "${entry.clazz}_${entry.name}".replace(" ", "_")
        val recordRef = childRecordsCollection.document(docId)
        val billingRef = firestore.collection("arborBillingQueue").document()

        val batch = firestore.batch()

        // Update the primary meal record.
        batch.set(
            recordRef,
            mapOf(
                "served" to true,
                "servedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )

        // Add a new entry to the billing queue for end-of-service processing.
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

    // Logs application errors and stack traces to a dedicated Firestore collection for debugging.
    fun logErrorToFirebase(context: String, error: Throwable, schoolName: String?) {
        val errorData = mapOf(
            "context" to context,
            "schoolName" to (schoolName ?: "unknown"),
            "message" to (error.message ?: "no message"),
            "stackTrace" to error.stackTraceToString(),
            "timestamp" to FieldValue.serverTimestamp()
        )
        firestore.collection("errorLog")
            .add(errorData)
            .addOnFailureListener {
                // Fail silently if logging itself fails.
            }
    }
}
