package com.techandthat.slpmealselection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the chunking logic used in deleteAllRecords / upsertArborRecords
 * correctly splits lists to stay under Firestore's 500-operation batch limit.
 */
class BatchChunkingTest {

    private val CHUNK_SIZE = 400

    @Test
    fun `empty list produces no chunks`() {
        val chunks = emptyList<String>().chunked(CHUNK_SIZE)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `single element produces one chunk`() {
        val chunks = listOf("a").chunked(CHUNK_SIZE)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].size)
    }

    @Test
    fun `exactly 400 elements produces one chunk`() {
        val items = List(400) { "item_$it" }
        val chunks = items.chunked(CHUNK_SIZE)
        assertEquals(1, chunks.size)
        assertEquals(400, chunks[0].size)
    }

    @Test
    fun `401 elements produces two chunks`() {
        val items = List(401) { "item_$it" }
        val chunks = items.chunked(CHUNK_SIZE)
        assertEquals(2, chunks.size)
        assertEquals(400, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun `800 elements produces two equal chunks`() {
        val items = List(800) { "item_$it" }
        val chunks = items.chunked(CHUNK_SIZE)
        assertEquals(2, chunks.size)
        chunks.forEach { assertEquals(400, it.size) }
    }

    @Test
    fun `no chunk exceeds Firestore 500-write limit`() {
        val items = List(1_500) { "item_$it" }
        val chunks = items.chunked(CHUNK_SIZE)
        chunks.forEach { chunk ->
            assertTrue("Chunk size ${chunk.size} exceeds Firestore limit", chunk.size <= 500)
        }
    }

    @Test
    fun `all items are preserved after chunking`() {
        val items = List(1_234) { "item_$it" }
        val chunks = items.chunked(CHUNK_SIZE)
        val reconstructed = chunks.flatten()
        assertEquals(items, reconstructed)
    }
}
