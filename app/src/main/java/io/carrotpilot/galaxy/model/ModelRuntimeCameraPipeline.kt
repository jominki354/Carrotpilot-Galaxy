package io.carrotpilot.galaxy.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

class ModelRuntimeCameraPipeline(
  private val scope: CoroutineScope,
  private val policy: ModelRuntimePolicy = ModelRuntimePolicy(),
  private val inferenceEngine: ModelInferenceEngine = OnnxPlaceholderInferenceEngine(),
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  private companion object {
    const val TIMEOUT_STREAK_THRESHOLD = 3
    const val LATENCY_WINDOW_SIZE = 120
  }

  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

  private var monitorJob: Job? = null
  @Volatile private var startedAtMs: Long = 0L
  @Volatile private var lastModelAtMs: Long = 0L
  @Volatile private var lastPoseAtMs: Long = 0L
  @Volatile private var modelFrameCount: Long = 0L
  @Volatile private var poseSampleCount: Long = 0L
  @Volatile private var sessionActive = false
  @Volatile private var inferenceReady: Boolean = false
  @Volatile private var inferenceOutputCount: Long = 0L
  @Volatile private var inferenceFailureCount: Long = 0L
  @Volatile private var inferenceLatencyP50Ms: Double = 0.0
  @Volatile private var inferenceLatencyP95Ms: Double = 0.0
  @Volatile private var inferenceLastFailure: String = "-"
  @Volatile private var modelTimeoutStreak: Int = 0
  @Volatile private var poseTimeoutStreak: Int = 0
  private val inferenceLatenciesMs = ArrayDeque<Double>()

  suspend fun startSession(permissionGranted: Boolean) {
    stopSession()
    resetCounters()

    if (!permissionGranted) {
      _state.value = ModelRuntimeState(
        stage = ModelRuntimeStage.ERROR,
        error = ModelRuntimeErrorCode.CAMERA_PERMISSION_DENIED,
        inferenceBackend = inferenceEngine.backendName,
        inferenceReady = false,
        inferenceOutputCount = 0L,
        inferenceFailures = 0L,
        inferenceLastFailure = "-",
        updatedAtMs = nowMs(),
      )
      return
    }

    inferenceReady = inferenceEngine.initialize()
    startedAtMs = nowMs()
    _state.value = ModelRuntimeState(
      stage = ModelRuntimeStage.CAMERA_READY,
      error = ModelRuntimeErrorCode.NONE,
      inferenceBackend = inferenceEngine.backendName,
      inferenceReady = inferenceReady,
      inferenceOutputCount = inferenceOutputCount,
      inferenceLatencyMsP50 = inferenceLatencyP50Ms,
      inferenceLatencyMsP95 = inferenceLatencyP95Ms,
      inferenceFailures = inferenceFailureCount,
      inferenceLastFailure = inferenceLastFailure,
      updatedAtMs = nowMs(),
    )
    sessionActive = true
    lastModelAtMs = startedAtMs
    lastPoseAtMs = startedAtMs

    monitorJob = scope.launch(Dispatchers.Default) {
      while (isActive && sessionActive) {
        delay(250L)
        val now = nowMs()
        val elapsedMs = max(1L, now - startedAtMs)
        val modelHz = modelFrameCount * 1000.0 / elapsedMs
        val poseHz = poseSampleCount * 1000.0 / elapsedMs
        val expectedFrames = elapsedMs / policy.modelIntervalMs.toDouble()
        val frameDropPerc = if (expectedFrames > 0.0) {
          max(0.0, ((expectedFrames - modelFrameCount) / expectedFrames) * 100.0)
        } else {
          0.0
        }

        val rawModelTimeout = modelFrameCount > 0 && now - lastModelAtMs > policy.modelTimeoutMs
        val rawPoseTimeout = poseSampleCount > 0 && now - lastPoseAtMs > policy.poseTimeoutMs
        modelTimeoutStreak = if (rawModelTimeout) modelTimeoutStreak + 1 else 0
        poseTimeoutStreak = if (rawPoseTimeout) poseTimeoutStreak + 1 else 0
        val modelTimeout = modelTimeoutStreak >= TIMEOUT_STREAK_THRESHOLD
        val poseTimeout = poseTimeoutStreak >= TIMEOUT_STREAK_THRESHOLD
        val stage = when {
          modelTimeout || poseTimeout -> ModelRuntimeStage.ERROR
          modelFrameCount == 0L -> ModelRuntimeStage.CAMERA_READY
          elapsedMs < 1_000L -> ModelRuntimeStage.MODEL_STREAMING
          else -> ModelRuntimeStage.STABLE
        }
        val error = when {
          modelTimeout -> ModelRuntimeErrorCode.MODEL_TIMEOUT
          poseTimeout -> ModelRuntimeErrorCode.POSE_TIMEOUT
          else -> ModelRuntimeErrorCode.NONE
        }

        _state.value = ModelRuntimeState(
          stage = stage,
          error = error,
          modelHz = modelHz,
          poseHz = poseHz,
          modelFrameCount = modelFrameCount,
          poseSampleCount = poseSampleCount,
          frameDropPerc = frameDropPerc,
          inferenceBackend = inferenceEngine.backendName,
          inferenceReady = inferenceReady,
          inferenceOutputCount = inferenceOutputCount,
          inferenceLatencyMsP50 = inferenceLatencyP50Ms,
          inferenceLatencyMsP95 = inferenceLatencyP95Ms,
          inferenceFailures = inferenceFailureCount,
          inferenceLastFailure = inferenceLastFailure,
          updatedAtMs = now,
        )
      }
    }
  }

  fun onCameraFrame(timestampMs: Long = nowMs()) {
    if (!sessionActive) return
    val observedAtMs = nowMs()
    modelFrameCount += 1
    poseSampleCount += 1
    // Keep timeout clock-domain consistent with monitor's nowMs().
    lastModelAtMs = observedAtMs
    lastPoseAtMs = observedAtMs
    if (inferenceReady) {
      applyInferenceResult(inferenceEngine.run(observedAtMs))
    }
  }

  fun onCameraSourceError() {
    if (!sessionActive) return
    _state.value = _state.value.copy(
      stage = ModelRuntimeStage.ERROR,
      error = ModelRuntimeErrorCode.MODEL_TIMEOUT,
      inferenceBackend = inferenceEngine.backendName,
      inferenceReady = inferenceReady,
      inferenceOutputCount = inferenceOutputCount,
      inferenceLatencyMsP50 = inferenceLatencyP50Ms,
      inferenceLatencyMsP95 = inferenceLatencyP95Ms,
      inferenceFailures = inferenceFailureCount,
      inferenceLastFailure = inferenceLastFailure,
      updatedAtMs = nowMs(),
    )
  }

  fun markPermissionDenied() {
    sessionActive = false
    monitorJob?.cancel()
    monitorJob = null
    resetCounters()
    _state.value = ModelRuntimeState(
      stage = ModelRuntimeStage.ERROR,
      error = ModelRuntimeErrorCode.CAMERA_PERMISSION_DENIED,
      modelHz = 0.0,
      poseHz = 0.0,
      modelFrameCount = 0L,
      poseSampleCount = 0L,
      frameDropPerc = 0.0,
      inferenceBackend = inferenceEngine.backendName,
      inferenceReady = false,
      inferenceOutputCount = 0L,
      inferenceFailures = 0L,
      inferenceLastFailure = "-",
      updatedAtMs = nowMs(),
    )
  }

  suspend fun stopSession() {
    sessionActive = false
    monitorJob?.cancel()
    monitorJob = null
    _state.value = _state.value.copy(
      stage = ModelRuntimeStage.STOPPED,
      error = ModelRuntimeErrorCode.NONE,
      updatedAtMs = nowMs(),
    )
  }

  fun reset() {
    resetCounters()
    inferenceEngine.release()
    _state.value = initialState()
  }

  private fun initialState(): ModelRuntimeState {
    return ModelRuntimeState(
      inferenceBackend = inferenceEngine.backendName,
      updatedAtMs = nowMs(),
    )
  }

  private fun applyInferenceResult(result: ModelInferenceResult) {
    if (result.success) {
      inferenceOutputCount += max(1, result.outputsProduced).toLong()
      inferenceLastFailure = "-"
    } else {
      inferenceFailureCount += 1
      inferenceLastFailure = result.failureReason?.ifBlank { "unknown" } ?: "unknown"
    }

    if (result.latencyMs > 0.0) {
      inferenceLatenciesMs.addLast(result.latencyMs)
      while (inferenceLatenciesMs.size > LATENCY_WINDOW_SIZE) {
        inferenceLatenciesMs.removeFirst()
      }
      val sorted = inferenceLatenciesMs.sorted()
      inferenceLatencyP50Ms = percentile(sorted, 0.50)
      inferenceLatencyP95Ms = percentile(sorted, 0.95)
    }
  }

  private fun percentile(sorted: List<Double>, ratio: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val index = ceil((sorted.size * ratio)).toInt().coerceAtLeast(1) - 1
    return sorted[index.coerceIn(0, sorted.lastIndex)]
  }

  private fun resetCounters() {
    startedAtMs = 0L
    lastModelAtMs = 0L
    lastPoseAtMs = 0L
    modelFrameCount = 0L
    poseSampleCount = 0L
    inferenceReady = false
    inferenceOutputCount = 0L
    inferenceFailureCount = 0L
    inferenceLatencyP50Ms = 0.0
    inferenceLatencyP95Ms = 0.0
    inferenceLastFailure = "-"
    inferenceLatenciesMs.clear()
    modelTimeoutStreak = 0
    poseTimeoutStreak = 0
  }
}
