package com.techandthat.slpmealselection.model

/**
 * Shared domain models used across the application to represent application state
 * and student meal information.
 */

// Enumerates the different operational modes of the tablet application.
enum class TabletType {
    KITCHEN, // Dashboard for kitchen staff to prepare and serve meals.
    STUDENT  // Step-by-step UI for students to select their names.
}

// Enumerates the UI states within the student tablet selection flow.
enum class StudentScreen {
    IDLE,            // Waiting for a student to approach.
    CLASS_SELECTION, // Student chooses their registration group/class.
    NAME_SELECTION,  // Student chooses their name from the class list.
    SUCCESS          // Confirmation that the selection was received.
}

// Represents a single meal selection for a student, including their name, class, and requirements.
data class MealEntry(
    val name: String,
    val clazz: String,
    val meal: String,
    val documentId: String? = null,
    val schoolName: String? = null,
    val dietaryRequirements: List<String> = emptyList(), // e.g., ["Nut Allergy", "Dairy Free"]
    var served: Boolean = false,                        // True if the kitchen has marked this plate as delivered.
    var checkedIn: Boolean = false                      // True if the student has confirmed their presence on the tablet.
)
