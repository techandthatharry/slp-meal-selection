package com.techandthat.slpmealselection

import com.techandthat.slpmealselection.model.TabletType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the kiosk login credential validation logic.
 * isCredentialValid() is a pure function with no Android dependencies — straightforward to test.
 */
class CredentialValidationTest {

    @Test
    fun `kitchen credentials accept correct username and password`() {
        assertTrue(isCredentialValid(TabletType.KITCHEN, "k", "k"))
    }

    @Test
    fun `child credentials accept correct username and password`() {
        assertTrue(isCredentialValid(TabletType.CHILD, "c", "c"))
    }

    @Test
    fun `kitchen rejects wrong password`() {
        assertFalse(isCredentialValid(TabletType.KITCHEN, "k", "wrongpassword"))
    }

    @Test
    fun `kitchen rejects child credentials`() {
        assertFalse(isCredentialValid(TabletType.KITCHEN, "c", "c"))
    }

    @Test
    fun `child rejects kitchen credentials`() {
        assertFalse(isCredentialValid(TabletType.CHILD, "k", "k"))
    }

    @Test
    fun `credentials reject empty strings`() {
        assertFalse(isCredentialValid(TabletType.KITCHEN, "", ""))
        assertFalse(isCredentialValid(TabletType.CHILD, "", ""))
    }
}
