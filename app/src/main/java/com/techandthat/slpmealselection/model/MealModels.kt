package com.techandthat.slpmealselection.model

// Defines shared UI/domain models used by kitchen and child tablet flows.

// Identifies whether the tablet is currently running in kitchen or child mode.
enum class TabletType {
    KITCHEN,
    CHILD
}

// Tracks which step of the child-facing check-in flow is currently active.
enum class ChildScreen {
    IDLE,
    CLASS_SELECTION,
    NAME_SELECTION,
    SUCCESS
}

// Represents one child meal record, metadata, and whether the meal has been served.
data class MealEntry(
    val name: String,
    val clazz: String,
    val meal: String,
    val documentId: String? = null,
    val schoolName: String? = null,
    val dietaryRequirements: List<String> = emptyList(),
    var served: Boolean = false
)
