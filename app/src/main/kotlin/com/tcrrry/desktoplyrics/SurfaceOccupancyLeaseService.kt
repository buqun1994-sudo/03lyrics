package com.tcrrry.desktoplyrics

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
}

internal class SurfaceOccupancyLeaseState {
    private val listeners = linkedSetOf<(Boolean) -> Unit>()
    private var occupied = false

    fun isOccupied(): Boolean = occupied

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(occupied)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    fun setOccupied(nextOccupied: Boolean) {
        if (occupied == nextOccupied) return
        occupied = nextOccupied
        listeners.toList().forEach { listener -> listener(nextOccupied) }
    }
}

internal object SurfaceOccupancyLeaseRegistry {
    private val state = SurfaceOccupancyLeaseState()

    fun addListener(listener: (Boolean) -> Unit) = state.addListener(listener)

    fun removeListener(listener: (Boolean) -> Unit) = state.removeListener(listener)

    fun setOccupied(occupied: Boolean) = state.setOccupied(occupied)
}

/** Binding lifetime is the lease; the Binder intentionally exposes no methods. */
class SurfaceOccupancyLeaseService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != SurfaceOccupancyLeaseContract.ACTION_ACQUIRE_OCCUPANCY_LEASE) {
            return null
        }
        Log.i(LOG_TAG, "Desktop surface occupancy lease acquired")
        SurfaceOccupancyLeaseRegistry.setOccupied(true)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(LOG_TAG, "Desktop surface occupancy lease released")
        SurfaceOccupancyLeaseRegistry.setOccupied(false)
        return false
    }

    override fun onDestroy() {
        SurfaceOccupancyLeaseRegistry.setOccupied(false)
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
    }
}
