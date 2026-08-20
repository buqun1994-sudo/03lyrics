package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceOccupancyLeaseStateTest {
    @Test
    fun `listener receives current state and distinct desktop lease transitions`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<IcarExternalSurfaceOccupancy>()

        state.addListener(changes::add)
        state.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, true)
        state.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, true)
        state.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, false)

        assertEquals(
            listOf(
                IcarExternalSurfaceOccupancy(),
                IcarExternalSurfaceOccupancy(desktopRegionOccupied = true),
                IcarExternalSurfaceOccupancy(),
            ),
            changes
        )
    }

    @Test
    fun `late listener observes an already active lease`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<IcarExternalSurfaceOccupancy>()

        state.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, true)
        state.addListener(changes::add)

        assertEquals(listOf(IcarExternalSurfaceOccupancy(fullDisplayOccupied = true)), changes)
    }

    @Test
    fun `removed listener no longer receives lease changes`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<IcarExternalSurfaceOccupancy>()
        val listener: (IcarExternalSurfaceOccupancy) -> Unit = { occupancy -> changes.add(occupancy) }

        state.addListener(listener)
        state.removeListener(listener)
        state.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, true)

        assertEquals(listOf(IcarExternalSurfaceOccupancy()), changes)
    }

    @Test
    fun `full display lease remains distinct while a desktop lease is active`() {
        val state = SurfaceOccupancyLeaseState()
        val changes = mutableListOf<IcarExternalSurfaceOccupancy>()

        state.addListener(changes::add)
        state.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, true)
        state.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, true)
        state.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, false)

        assertEquals(
            listOf(
                IcarExternalSurfaceOccupancy(),
                IcarExternalSurfaceOccupancy(desktopRegionOccupied = true),
                IcarExternalSurfaceOccupancy(
                    desktopRegionOccupied = true,
                    fullDisplayOccupied = true,
                ),
                IcarExternalSurfaceOccupancy(desktopRegionOccupied = true),
            ),
            changes
        )
    }
}
