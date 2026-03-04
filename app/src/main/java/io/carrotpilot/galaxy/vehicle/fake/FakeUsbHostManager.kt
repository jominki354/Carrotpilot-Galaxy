package io.carrotpilot.galaxy.vehicle.fake

import io.carrotpilot.galaxy.vehicle.UsbConnectionState
import io.carrotpilot.galaxy.vehicle.UsbHostManager
import io.carrotpilot.galaxy.vehicle.PandaUsbSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeUsbHostManager(
  private val scenario: FakeScenario = FakeScenario.HAPPY_PATH,
) : UsbHostManager {
  private val _state = MutableStateFlow(UsbConnectionState.DISCONNECTED)
  override val state: StateFlow<UsbConnectionState> = _state.asStateFlow()
  override fun sessionOrNull(): PandaUsbSession? = null

  override suspend fun ensurePermission(): Boolean {
    val permissionGranted = scenario != FakeScenario.USB_PERMISSION_DENIED
    _state.value = if (permissionGranted) {
      UsbConnectionState.PERMISSION_GRANTED
    } else {
      UsbConnectionState.PERMISSION_REQUIRED
    }
    return permissionGranted
  }

  override suspend fun connect(): Boolean {
    val connectSuccess = scenario != FakeScenario.PANDA_CONNECT_FAIL
    _state.value = if (connectSuccess) UsbConnectionState.CONNECTED else UsbConnectionState.ERROR
    return connectSuccess
  }

  override suspend fun disconnect() {
    _state.value = UsbConnectionState.DISCONNECTED
  }
}
