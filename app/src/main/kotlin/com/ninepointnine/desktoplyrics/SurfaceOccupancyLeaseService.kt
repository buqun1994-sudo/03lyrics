package com.ninepointnine.desktoplyrics

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

internal object SurfaceOccupancyLeaseContract {
    const val ACTION_ACQUIRE_OCCUPANCY_LEASE =
        "com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE"
    const val METADATA_PROTOCOL_VERSION =
        "com.tcrrry.icar.surface.OCCUPANCY_PROTOCOL_VERSION"
    const val PROTOCOL_VERSION = 1

    const val ACTION_ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE =
        "com.tcrrry.icar.surface.action.ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE"
    const val METADATA_FULL_DISPLAY_PROTOCOL_VERSION =
        "com.tcrrry.icar.surface.FULL_DISPLAY_OCCUPANCY_PROTOCOL_VERSION"
    const val FULL_DISPLAY_PROTOCOL_VERSION = 1
}

internal enum class SurfaceOccupancyLeaseKind {
    DESKTOP_REGION,
    FULL_DISPLAY,
}

internal class SurfaceOccupancyLeaseState {
    private val listeners = linkedSetOf<(IcarExternalSurfaceOccupancy) -> Unit>()
    private var occupancy = IcarExternalSurfaceOccupancy()

    fun current(): IcarExternalSurfaceOccupancy = occupancy

    fun addListener(listener: (IcarExternalSurfaceOccupancy) -> Unit) {
        listeners += listener
        listener(occupancy)
    }

    fun removeListener(listener: (IcarExternalSurfaceOccupancy) -> Unit) {
        listeners -= listener
    }

    fun setOccupied(kind: SurfaceOccupancyLeaseKind, occupied: Boolean) {
        val next = when (kind) {
            SurfaceOccupancyLeaseKind.DESKTOP_REGION -> {
                occupancy.copy(desktopRegionOccupied = occupied)
            }
            SurfaceOccupancyLeaseKind.FULL_DISPLAY -> {
                occupancy.copy(fullDisplayOccupied = occupied)
            }
        }
        if (occupancy == next) return
        occupancy = next
        listeners.toList().forEach { listener -> listener(next) }
    }
}

internal object SurfaceOccupancyLeaseRegistry {
    private val state = SurfaceOccupancyLeaseState()

    fun addListener(listener: (IcarExternalSurfaceOccupancy) -> Unit) = state.addListener(listener)

    fun removeListener(listener: (IcarExternalSurfaceOccupancy) -> Unit) = state.removeListener(listener)

    fun setOccupied(kind: SurfaceOccupancyLeaseKind, occupied: Boolean) =
        state.setOccupied(kind, occupied)
}

/** Binding lifetime is the lease; the Binder intentionally exposes no methods. */
class SurfaceOccupancyLeaseService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != SurfaceOccupancyLeaseContract.ACTION_ACQUIRE_OCCUPANCY_LEASE) {
            return null
        }
        Log.i(LOG_TAG, "Desktop surface occupancy lease acquired")
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, true)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(LOG_TAG, "Desktop surface occupancy lease released")
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, false)
        return false
    }

    override fun onDestroy() {
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.DESKTOP_REGION, false)
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
    }
}

/** Binding lifetime is an exclusive full-display lease for cooperating apps. */
class FullDisplayOccupancyLeaseService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != SurfaceOccupancyLeaseContract.ACTION_ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE) {
            return null
        }
        Log.i(LOG_TAG, "Full-display occupancy lease acquired")
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, true)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(LOG_TAG, "Full-display occupancy lease released")
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, false)
        return false
    }

    override fun onDestroy() {
        SurfaceOccupancyLeaseRegistry.setOccupied(SurfaceOccupancyLeaseKind.FULL_DISPLAY, false)
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "DesktopLyrics"
    }
}
