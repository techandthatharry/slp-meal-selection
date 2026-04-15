package com.techandthat.slpmealselection.network

import com.techandthat.slpmealselection.data.ChildRecordsRepository

// Converts callable Arbor payload data into Firestore-ready student records.
object ArborPayloadMapper {
    // Maps raw function response map into validated ArborStudentRecord objects.
    fun mapStudentRecords(data: Map<String, Any>?): List<ChildRecordsRepository.ArborStudentRecord> {
        @Suppress("UNCHECKED_CAST")
        val students = data?.get("students") as? List<Map<String, Any>>
        if (students.isNullOrEmpty()) return emptyList()

        return students.mapNotNull { student ->
            val documentId = student["documentId"] as? String
            val childName = student["childName"] as? String
            val className = student["className"] as? String
            val schoolName = student["schoolName"] as? String
            val source = student["source"] as? String ?: "arbor"
            val mealSelected = student["mealSelected"] as? String ?: "Not selected"

            @Suppress("UNCHECKED_CAST")
            val dietaryRequirements = (student["dietaryRequirements"] as? List<Any>)
                ?.mapNotNull { it as? String }
                ?: emptyList()

            // Drop malformed rows so repository writes only complete records.
            if (documentId.isNullOrBlank() || childName.isNullOrBlank() || className.isNullOrBlank() || schoolName.isNullOrBlank()) {
                null
            } else {
                ChildRecordsRepository.ArborStudentRecord(
                    documentId = documentId,
                    childName = childName,
                    className = className,
                    schoolName = schoolName,
                    source = source,
                    mealSelected = mealSelected,
                    dietaryRequirements = dietaryRequirements
                )
            }
        }
    }
}
