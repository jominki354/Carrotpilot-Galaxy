package io.carrotpilot.galaxy.model

enum class ModelRuntimeStage {
  STOPPED,
  CAMERA_READY,
  MODEL_STREAMING,
  LOCATION_STREAMING,
  STABLE,
  ERROR,
}

enum class ModelRuntimeErrorCode {
  NONE,
  CAMERA_PERMISSION_DENIED,
  MODEL_TIMEOUT,
  POSE_TIMEOUT,
}

data class ModelRuntimePolicy(
  val modelIntervalMs: Long = 50L,
  val poseIntervalMs: Long = 10L,
  val modelTimeoutMs: Long = 600L,
  val poseTimeoutMs: Long = 400L,
)

data class ModelRuntimeState(
  val stage: ModelRuntimeStage = ModelRuntimeStage.STOPPED,
  val error: ModelRuntimeErrorCode = ModelRuntimeErrorCode.NONE,
  val modelHz: Double = 0.0,
  val poseHz: Double = 0.0,
  val modelFrameCount: Long = 0L,
  val poseSampleCount: Long = 0L,
  val frameDropPerc: Double = 0.0,
  val inferenceBackend: String = "NONE",
  val inferenceReady: Boolean = false,
  val inferenceOutputCount: Long = 0L,
  val inferenceLatencyMsP50: Double = 0.0,
  val inferenceLatencyMsP95: Double = 0.0,
  val inferenceFailures: Long = 0L,
  val inferenceLastFailure: String = "-",
  val updatedAtMs: Long = System.currentTimeMillis(),
)
