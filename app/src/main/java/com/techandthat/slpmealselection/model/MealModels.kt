package com.techandthat.slpmealselection.model

enum class TabletType {
    KITCHEN,
    CHILD
}

enum class ChildScreen {
    IDLE,
    CLASS_SELECTION,
    NAME_SELECTION,
    SUCCESS
}

data class MealEntry(
    val name: String,
    val clazz: String,
    val meal: String,
    var served: Boolean = false
)
