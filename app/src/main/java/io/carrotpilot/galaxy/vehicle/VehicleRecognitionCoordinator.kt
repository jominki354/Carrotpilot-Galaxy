package io.carrotpilot.galaxy.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VehicleRecognitionCoordinator(
  private val scope: CoroutineScope,
  private val usbHostManager: UsbHostManager,
  private val canIngestSource: CanIngestSource,
  private val fingerprintEngine: FingerprintEngine,
  private val carInterfaceLoader: CarInterfaceLoader = DefaultCarInterfaceLoader(),
  private val machine: VehicleRecognitionStateMachine = VehicleRecognitionStateMachine(),
  private val policy: VehicleRecognitionPolicy = VehicleRecognitionPolicy(),
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  private val _state = MutableStateFlow(machine.initialState())
  val state: StateFlow<VehicleRecognitionState> = _state.asStateFlow()

  private var collectorJob: Job? = null
  private var monitorJob: Job? = null
  private var lastFrameAtMs: Long = 0L

  suspend fun start() {
    if (collectorJob?.isActive == true || monitorJob?.isActive == true) return
    if (collectorJob?.isCompleted == true) collectorJob = null
    if (monitorJob?.isCompleted == true) monitorJob = null

    val permissionOk = usbHostManager.ensurePermission()
    _state.value = machine.transition(_state.value, VehicleEvent.UsbPermissionResult(permissionOk))
    if (!permissionOk) return

    val connectOk = usbHostManager.connect()
    _state.value = if (connectOk) {
      machine.transition(_state.value, VehicleEvent.PandaConnected)
    } else {
      machine.transition(_state.value, VehicleEvent.PandaConnectFailed)
    }
    if (!connectOk) return

    fingerprintEngine.reset()
    canIngestSource.start()
    lastFrameAtMs = nowMs()

    collectorJob?.cancel()
    collectorJob = scope.launch {
      canIngestSource.frames.collect { frame ->
        if (_state.value.stage == VehicleStage.PANDA_CONNECTED) {
          _state.value = machine.transition(_state.value, VehicleEvent.CanRxStable)
          _state.value = machine.transition(_state.value, VehicleEvent.StartFingerprinting)
        }

        lastFrameAtMs = nowMs()
        val progress = fingerprintEngine.onFrame(frame)
        _state.value = machine.transition(
          _state.value,
          VehicleEvent.FingerprintProgressUpdated(
            candidates = progress.candidates,
            observedSignatureCount = progress.observedSignatureCount,
          ),
        )

        if (progress.identifiedCar != null &&
          _state.value.identifiedCarFingerprint == null &&
          _state.value.stage != VehicleStage.SAFETY_READY
        ) {
          _state.value = machine.transition(
            _state.value,
            VehicleEvent.FingerprintIdentified(progress.identifiedCar),
          )
          when (val load = carInterfaceLoader.load(progress.identifiedCar)) {
            is CarInterfaceLoadResult.Success -> {
              _state.value = machine.transition(_state.value, VehicleEvent.CarParamsPublished(load.carParams))
              _state.value = machine.transition(_state.value, VehicleEvent.SafetyReady)
            }

            is CarInterfaceLoadResult.Failure -> {
              _state.value = machine.transition(_state.value, VehicleEvent.InterfaceLoadFailed(load.reason))
            }
          }
        }
      }
    }

    monitorJob?.cancel()
    monitorJob = scope.launch {
      val startedAt = nowMs()
      while (true) {
        delay(250)
        val elapsed = nowMs() - startedAt
        val sinceLastFrame = nowMs() - lastFrameAtMs

        if (_state.value.stage == VehicleStage.FINGERPRINTING &&
          elapsed > policy.fingerprintTimeoutMs &&
          _state.value.identifiedCarFingerprint == null
        ) {
          _state.value = machine.transition(_state.value, VehicleEvent.FingerprintTimedOut)
          break
        }

        if (sinceLastFrame > policy.canTimeoutMs &&
          _state.value.stage >= VehicleStage.CAN_RX_STABLE
        ) {
          _state.value = machine.transition(_state.value, VehicleEvent.CanTimedOut)
        }
      }
    }
  }

  suspend fun stop() {
    collectorJob?.cancel()
    monitorJob?.cancel()
    collectorJob = null
    monitorJob = null
    canIngestSource.stop()
    usbHostManager.disconnect()
    _state.value = machine.transition(_state.value, VehicleEvent.Disconnect)
  }

  fun reset() {
    _state.value = machine.transition(_state.value, VehicleEvent.Reset)
  }
}
