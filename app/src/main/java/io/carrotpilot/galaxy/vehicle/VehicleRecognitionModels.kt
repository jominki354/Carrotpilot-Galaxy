package io.carrotpilot.galaxy.vehicle

enum class VehicleStage {
  DISCONNECTED,
  USB_PERMISSION_READY,
  PANDA_CONNECTED,
  CAN_RX_STABLE,
  FINGERPRINTING,
  CAR_IDENTIFIED,
  CARPARAMS_PUBLISHED,
  SAFETY_READY,
}

enum class VehicleErrorCode {
  NONE,
  USB_PERMISSION_DENIED,
  PANDA_CONNECT_FAIL,
  CAN_TIMEOUT,
  FINGERPRINT_TIMEOUT,
  INTERFACE_LOAD_FAIL,
}

data class VehicleRecognitionPolicy(
  val usbPermissionTimeoutMs: Long = 10_000,
  val pandaConnectMaxRetries: Int = 3,
  val canTimeoutMs: Long = 3_000,
  val fingerprintTimeoutMs: Long = 5_000,
)

data class CarParams(
  val carFingerprint: String,
  val safetyModel: String,
  val safetyParam: Int,
)

data class VehicleRecognitionState(
  val stage: VehicleStage = VehicleStage.DISCONNECTED,
  val error: VehicleErrorCode = VehicleErrorCode.NONE,
  val pandaConnectRetries: Int = 0,
  val identifiedCarFingerprint: String? = null,
  val carParams: CarParams? = null,
  val interfaceLoadFailureReason: String? = null,
  val candidateCars: Set<String> = emptySet(),
  val observedSignatureCount: Int = 0,
  val updatedAtMs: Long = System.currentTimeMillis(),
)

sealed interface VehicleEvent {
  data class UsbPermissionResult(val granted: Boolean) : VehicleEvent
  data object PandaConnected : VehicleEvent
  data object PandaConnectFailed : VehicleEvent
  data object CanRxStable : VehicleEvent
  data object StartFingerprinting : VehicleEvent
  data class FingerprintProgressUpdated(
    val candidates: Set<String>,
    val observedSignatureCount: Int,
  ) : VehicleEvent
  data class FingerprintIdentified(val carFingerprint: String) : VehicleEvent
  data object FingerprintTimedOut : VehicleEvent
  data class InterfaceLoadFailed(val reason: String) : VehicleEvent
  data class CarParamsPublished(val carParams: CarParams) : VehicleEvent
  data object SafetyReady : VehicleEvent
  data object CanTimedOut : VehicleEvent
  data object Disconnect : VehicleEvent
  data object Reset : VehicleEvent
}
