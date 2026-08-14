package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceOccupancyLeaseStateTest {
    @Test
    fun `listener receives current state and distinct lease transitions`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<Boolean>()

        state.addListener(changes::add)
        state.setOccupied(true)
        state.setOccupied(true)
        state.setOccupied(false)

        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun `late listener observes an already active lease`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<Boolean>()

        state.setOccupied(true)
        state.addListener(changes::add)

        assertEquals(listOf(true), changes)
    }

    @Test
    fun `removed listener no longer receives lease changes`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { occupied -> changes.add(occupied) }

        state.addListener(listener)
        state.removeListener(listener)
        state.setOccupied(true)

        assertEquals(listOf(false), changes)
    }
}
