package io.carrotpilot.galaxy.vehicle

import kotlinx.coroutines.flow.StateFlow

enum class UsbConnectionState {
  DISCONNECTED,
  PERMISSION_REQUIRED,
  PERMISSION_GRANTED,
  CONNECTED,
  ERROR,
}

interface UsbHostManager {
  val state: StateFlow<UsbConnectionState>
  fun sessionOrNull(): PandaUsbSession?
  suspend fun ensurePermission(): Boolean
  suspend fun connect(): Boolean
  suspend fun disconnect()
}
