package com.techandthat.slpmealselection

import com.techandthat.slpmealselection.model.ChildScreen
import com.techandthat.slpmealselection.model.MealEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state machine extracted from the business logic that drives the tablet synchronisation flow.
 *
 * This class mirrors the boolean flags in MainActivity so that the key transitions can be
 * verified in isolation, without Android/Firebase dependencies.
 */
data class ServiceState(
    val serviceStarted: Boolean = false,
    val paused: Boolean = false,
    val activeOrder: MealEntry? = null,
    val showWaitingOverlay: Boolean = false,
    val childScreen: ChildScreen = ChildScreen.IDLE,
    val selectedClass: String? = null
) {
    fun applyFirestoreStatus(status: String): ServiceState = when (status) {
        "ACTIVE" -> copy(serviceStarted = true, paused = false)
        "PAUSED" -> copy(serviceStarted = true, paused = true)
        "IDLE", "ENDED" -> copy(serviceStarted = false, paused = false)
        else -> this
    }

    fun childSelectsClass(className: String): ServiceState =
        copy(selectedClass = className, childScreen = ChildScreen.NAME_SELECTION)

    fun childSelectsMeal(entry: MealEntry): ServiceState =
        copy(activeOrder = entry, childScreen = ChildScreen.SUCCESS)

    fun childConfirms(): ServiceState =
        copy(showWaitingOverlay = true, childScreen = ChildScreen.NAME_SELECTION)

    fun kitchenServesOrder(): ServiceState =
        copy(activeOrder = null, showWaitingOverlay = false)

    // Mirrors renderAppContent() auto-pickup logic: kitchen picks first checked-in, unserved entry.
    fun kitchenAutoPickup(database: List<MealEntry>): ServiceState {
        if (!serviceStarted || activeOrder != null) return this
        val next = database.firstOrNull { it.checkedIn && !it.served }
        return copy(activeOrder = next)
    }

    // Mirrors renderAppContent() overlay-clear logic: clear waiting state when served entry appears.
    fun childAutoUnlock(database: List<MealEntry>): ServiceState {
        if (activeOrder == null) return this
        val current = database.find { it.documentId == activeOrder.documentId }
        return if (current?.served == true) copy(activeOrder = null, showWaitingOverlay = false)
        else this
    }
}

class ServiceStateMachineTest {

    private val sampleMeal = MealEntry(
        name = "Liam Smith",
        clazz = "Year 1",
        meal = "Tomato Pasta",
        documentId = "Year_1_Liam_Smith"
    )

    // --- Firestore status → local state mapping ---

    @Test
    fun `IDLE status keeps service not started`() {
        val state = ServiceState().applyFirestoreStatus("IDLE")
        assertFalse(state.serviceStarted)
        assertFalse(state.paused)
    }

    @Test
    fun `ACTIVE status starts service and clears pause`() {
        val state = ServiceState(paused = true).applyFirestoreStatus("ACTIVE")
        assertTrue(state.serviceStarted)
        assertFalse(state.paused)
    }

    @Test
    fun `PAUSED status marks service started and paused`() {
        val state = ServiceState().applyFirestoreStatus("PAUSED")
        assertTrue(state.serviceStarted)
        assertTrue(state.paused)
    }

    @Test
    fun `ENDED status stops service`() {
        val state = ServiceState(serviceStarted = true).applyFirestoreStatus("ENDED")
        assertFalse(state.serviceStarted)
    }

    @Test
    fun `unknown status leaves state unchanged`() {
        val initial = ServiceState(serviceStarted = true)
        val after = initial.applyFirestoreStatus("UNKNOWN_VALUE")
        assertEquals(initial, after)
    }

    // --- Child selection flow ---

    @Test
    fun `child selects class and advances to name selection`() {
        val state = ServiceState().childSelectsClass("Year 1")
        assertEquals("Year 1", state.selectedClass)
        assertEquals(ChildScreen.NAME_SELECTION, state.childScreen)
    }

    @Test
    fun `child selects name and advances to success screen`() {
        val state = ServiceState()
            .childSelectsClass("Year 1")
            .childSelectsMeal(sampleMeal)

        assertNotNull(state.activeOrder)
        assertEquals(sampleMeal, state.activeOrder)
        assertEquals(ChildScreen.SUCCESS, state.childScreen)
    }

    @Test
    fun `child confirming meal shows waiting overlay`() {
        val state = ServiceState()
            .childSelectsMeal(sampleMeal)
            .childConfirms()

        assertTrue(state.showWaitingOverlay)
        assertEquals(ChildScreen.NAME_SELECTION, state.childScreen)
    }

    // --- Kitchen serving flow ---

    @Test
    fun `kitchen auto-pickup finds first checked-in unserved entry`() {
        val db = listOf(
            sampleMeal.copy(checkedIn = false, served = false),
            sampleMeal.copy(name = "Emma Jones", documentId = "Year_1_Emma_Jones", checkedIn = true, served = false),
            sampleMeal.copy(name = "Noah Williams", documentId = "Year_1_Noah_Williams", checkedIn = true, served = true)
        )
        val state = ServiceState(serviceStarted = true).kitchenAutoPickup(db)
        assertEquals("Emma Jones", state.activeOrder?.name)
    }

    @Test
    fun `kitchen auto-pickup skips already-served entries`() {
        val db = listOf(
            sampleMeal.copy(checkedIn = true, served = true)
        )
        val state = ServiceState(serviceStarted = true).kitchenAutoPickup(db)
        assertNull(state.activeOrder)
    }

    @Test
    fun `kitchen auto-pickup is a no-op when order already active`() {
        val existing = sampleMeal.copy(checkedIn = true)
        val state = ServiceState(serviceStarted = true, activeOrder = existing)
            .kitchenAutoPickup(listOf(sampleMeal.copy(checkedIn = true, served = false)))
        assertEquals(existing, state.activeOrder)
    }

    @Test
    fun `kitchen serving clears active order and overlay`() {
        val state = ServiceState(activeOrder = sampleMeal, showWaitingOverlay = true)
            .kitchenServesOrder()

        assertNull(state.activeOrder)
        assertFalse(state.showWaitingOverlay)
    }

    // --- Cross-tablet unlock ---

    @Test
    fun `child tablet unlocks when its active order is marked served in Firestore`() {
        val servedInDb = sampleMeal.copy(documentId = "Year_1_Liam_Smith", served = true)
        val state = ServiceState(activeOrder = sampleMeal, showWaitingOverlay = true)
            .childAutoUnlock(listOf(servedInDb))

        assertNull(state.activeOrder)
        assertFalse(state.showWaitingOverlay)
    }

    @Test
    fun `child tablet stays locked while its order is not yet served`() {
        val stillPending = sampleMeal.copy(served = false)
        val state = ServiceState(activeOrder = sampleMeal, showWaitingOverlay = true)
            .childAutoUnlock(listOf(stillPending))

        assertNotNull(state.activeOrder)
        assertTrue(state.showWaitingOverlay)
    }

    // --- Full happy-path flow ---

    @Test
    fun `full service cycle transitions correctly end-to-end`() {
        // 1. Kitchen starts service
        var childState = ServiceState().applyFirestoreStatus("ACTIVE")
        assertTrue(childState.serviceStarted)

        // 2. Child selects class and name
        childState = childState
            .childSelectsClass("Year 1")
            .childSelectsMeal(sampleMeal)
            .childConfirms()
        assertTrue(childState.showWaitingOverlay)

        // 3. Kitchen sees the checked-in order
        val dbAfterCheckIn = listOf(sampleMeal.copy(checkedIn = true, served = false))
        var kitchenState = ServiceState(serviceStarted = true).kitchenAutoPickup(dbAfterCheckIn)
        assertNotNull(kitchenState.activeOrder)

        // 4. Kitchen serves the meal
        kitchenState = kitchenState.kitchenServesOrder()
        assertNull(kitchenState.activeOrder)

        // 5. Firestore marks entry as served → child unlocks
        val dbAfterServed = listOf(sampleMeal.copy(documentId = sampleMeal.documentId, served = true))
        childState = childState.childAutoUnlock(dbAfterServed)
        assertFalse(childState.showWaitingOverlay)
        assertNull(childState.activeOrder)

        // 6. Service ends → child returns to idle
        childState = childState.applyFirestoreStatus("IDLE")
        assertFalse(childState.serviceStarted)
    }
}
