package io.carrotpilot.galaxy.driving

enum class DrivingCoreStage {
  STOPPED,
  DISABLED,
  PRE_ENABLED,
  ENABLED,
  SOFT_DISABLING,
  OVERRIDING,
}

enum class DrivingCoreErrorCode {
  NONE,
  NO_ENTRY,
  IMMEDIATE_DISABLE,
  SOFT_DISABLE,
  SAFETY_NOT_READY,
  MODEL_NOT_READY,
  VEHICLE_NOT_READY,
}

data class DrivingCoreInputSnapshot(
  val enableRequested: Boolean = false,
  val preEnable: Boolean = false,
  val noEntry: Boolean = true,
  val userDisable: Boolean = false,
  val immediateDisable: Boolean = false,
  val softDisable: Boolean = false,
  val overrideLateral: Boolean = false,
  val overrideLongitudinal: Boolean = false,
  val carRecognized: Boolean = false,
  val carParamsValid: Boolean = false,
  val safetyReady: Boolean = false,
  val modelFresh: Boolean = false,
  val vehicleFresh: Boolean = false,
  val calibrationValid: Boolean = true,
)

data class DrivingCoreState(
  val stage: DrivingCoreStage = DrivingCoreStage.STOPPED,
  val error: DrivingCoreErrorCode = DrivingCoreErrorCode.NONE,
  val latActive: Boolean = false,
  val longActive: Boolean = false,
  val sendcanAllowed: Boolean = false,
  val plannerHz: Double = 0.0,
  val controlHz: Double = 0.0,
  val plannerTicks: Long = 0L,
  val controlTicks: Long = 0L,
  val updatedAtMs: Long = System.currentTimeMillis(),
)
