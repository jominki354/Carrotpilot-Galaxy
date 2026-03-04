package io.carrotpilot.galaxy.vehicle

class VehicleRecognitionStateMachine(
  private val policy: VehicleRecognitionPolicy = VehicleRecognitionPolicy(),
  private val now: () -> Long = { System.currentTimeMillis() },
) {

  fun initialState(): VehicleRecognitionState = VehicleRecognitionState(updatedAtMs = now())

  fun transition(
    current: VehicleRecognitionState,
    event: VehicleEvent,
  ): VehicleRecognitionState {
    val t = now()
    return when (event) {
      is VehicleEvent.UsbPermissionResult -> {
        if (event.granted) {
          current.copy(
            stage = VehicleStage.USB_PERMISSION_READY,
            error = VehicleErrorCode.NONE,
            updatedAtMs = t,
          )
        } else {
          current.copy(
            stage = VehicleStage.DISCONNECTED,
            error = VehicleErrorCode.USB_PERMISSION_DENIED,
            updatedAtMs = t,
          )
        }
      }

      VehicleEvent.PandaConnected -> {
        current.copy(
          stage = VehicleStage.PANDA_CONNECTED,
          error = VehicleErrorCode.NONE,
          interfaceLoadFailureReason = null,
          updatedAtMs = t,
        )
      }

      VehicleEvent.PandaConnectFailed -> {
        val retries = current.pandaConnectRetries + 1
        if (retries >= policy.pandaConnectMaxRetries) {
          current.copy(
            stage = VehicleStage.DISCONNECTED,
            error = VehicleErrorCode.PANDA_CONNECT_FAIL,
            pandaConnectRetries = retries,
            updatedAtMs = t,
          )
        } else {
          current.copy(
            stage = VehicleStage.USB_PERMISSION_READY,
            error = VehicleErrorCode.PANDA_CONNECT_FAIL,
            pandaConnectRetries = retries,
            updatedAtMs = t,
          )
        }
      }

      VehicleEvent.CanRxStable -> {
        current.copy(
          stage = VehicleStage.CAN_RX_STABLE,
          error = VehicleErrorCode.NONE,
          updatedAtMs = t,
        )
      }

      VehicleEvent.StartFingerprinting -> {
        current.copy(
          stage = VehicleStage.FINGERPRINTING,
          error = VehicleErrorCode.NONE,
          interfaceLoadFailureReason = null,
          updatedAtMs = t,
        )
      }

      is VehicleEvent.FingerprintProgressUpdated -> {
        current.copy(
          candidateCars = event.candidates,
          observedSignatureCount = event.observedSignatureCount,
          updatedAtMs = t,
        )
      }

      is VehicleEvent.FingerprintIdentified -> {
        current.copy(
          stage = VehicleStage.CAR_IDENTIFIED,
          error = VehicleErrorCode.NONE,
          identifiedCarFingerprint = event.carFingerprint,
          interfaceLoadFailureReason = null,
          updatedAtMs = t,
        )
      }

      VehicleEvent.FingerprintTimedOut -> {
        current.copy(
          stage = VehicleStage.CAN_RX_STABLE,
          error = VehicleErrorCode.FINGERPRINT_TIMEOUT,
          updatedAtMs = t,
        )
      }

      is VehicleEvent.InterfaceLoadFailed -> {
        current.copy(
          stage = VehicleStage.CAR_IDENTIFIED,
          error = VehicleErrorCode.INTERFACE_LOAD_FAIL,
          interfaceLoadFailureReason = event.reason,
          updatedAtMs = t,
        )
      }

      is VehicleEvent.CarParamsPublished -> {
        current.copy(
          stage = VehicleStage.CARPARAMS_PUBLISHED,
          error = VehicleErrorCode.NONE,
          carParams = event.carParams,
          interfaceLoadFailureReason = null,
          updatedAtMs = t,
        )
      }

      VehicleEvent.SafetyReady -> {
        current.copy(
          stage = VehicleStage.SAFETY_READY,
          error = VehicleErrorCode.NONE,
          updatedAtMs = t,
        )
      }

      VehicleEvent.CanTimedOut -> {
        current.copy(
          stage = VehicleStage.PANDA_CONNECTED,
          error = VehicleErrorCode.CAN_TIMEOUT,
          updatedAtMs = t,
        )
      }

      VehicleEvent.Disconnect -> {
        current.copy(
          stage = VehicleStage.DISCONNECTED,
          error = VehicleErrorCode.NONE,
          identifiedCarFingerprint = null,
          carParams = null,
          interfaceLoadFailureReason = null,
          candidateCars = emptySet(),
          observedSignatureCount = 0,
          updatedAtMs = t,
        )
      }

      VehicleEvent.Reset -> initialState()
    }
  }
}
