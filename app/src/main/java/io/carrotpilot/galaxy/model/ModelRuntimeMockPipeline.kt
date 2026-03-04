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

class ModelRuntimeMockPipeline(
  private val scope: CoroutineScope,
  private val policy: ModelRuntimePolicy = ModelRuntimePolicy(),
  private val inferenceEngine: ModelInferenceEngine = MockModelInferenceEngine(),
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  private companion object {
    const val LATENCY_WINDOW_SIZE = 120
  }

  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

  private var modelJob: Job? = null
  private var poseJob: Job? = null
  private var monitorJob: Job? = null

  private var startedAtMs: Long = 0L
  private var lastModelAtMs: Long = 0L
  private var lastPoseAtMs: Long = 0L
  private var modelFrameCount: Long = 0L
  private var poseSampleCount: Long = 0L
  private var inferenceReady: Boolean = false
  private var inferenceOutputCount: Long = 0L
  private var inferenceFailureCount: Long = 0L
  private var inferenceLatencyP50Ms: Double = 0.0
  private var inferenceLatencyP95Ms: Double = 0.0
  private var inferenceLastFailure: String = "-"
  private val inferenceLatenciesMs = ArrayDeque<Double>()

  suspend fun start(scenario: ModelRuntimeMockScenario) {
    stop()
    resetCounters()

    if (scenario == ModelRuntimeMockScenario.CAMERA_PERMISSION_DENIED) {
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
    lastModelAtMs = startedAtMs
    lastPoseAtMs = startedAtMs
    _state.value = _state.value.copy(
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

    modelJob = scope.launch(Dispatchers.Default) {
      while (isActive) {
        if (scenario == ModelRuntimeMockScenario.MODEL_TIMEOUT && nowMs() - startedAtMs > 1_200L) {
          delay(100L)
          continue
        }
        modelFrameCount += 1
        lastModelAtMs = nowMs()
        if (inferenceReady) {
          applyInferenceResult(inferenceEngine.run(lastModelAtMs))
        }
        delay(policy.modelIntervalMs)
      }
    }

    poseJob = scope.launch(Dispatchers.Default) {
      while (isActive) {
        if (scenario == ModelRuntimeMockScenario.POSE_TIMEOUT && nowMs() - startedAtMs > 1_200L) {
          delay(100L)
          continue
        }
        poseSampleCount += 1
        lastPoseAtMs = nowMs()
        delay(policy.poseIntervalMs)
      }
    }

    monitorJob = scope.launch(Dispatchers.Default) {
      while (isActive) {
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

        val modelTimeout = now - lastModelAtMs > policy.modelTimeoutMs
        val poseTimeout = now - lastPoseAtMs > policy.poseTimeoutMs
        val stage = when {
          modelTimeout || poseTimeout -> ModelRuntimeStage.ERROR
          modelFrameCount == 0L && poseSampleCount == 0L -> ModelRuntimeStage.CAMERA_READY
          modelFrameCount > 0L && poseSampleCount == 0L -> ModelRuntimeStage.MODEL_STREAMING
          modelFrameCount > 0L && poseSampleCount > 0L && elapsedMs < 1_000L -> ModelRuntimeStage.LOCATION_STREAMING
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

        if (stage == ModelRuntimeStage.ERROR) {
          modelJob?.cancel()
          poseJob?.cancel()
          modelJob = null
          poseJob = null
          break
        }
      }
    }
  }

  suspend fun stop() {
    modelJob?.cancel()
    poseJob?.cancel()
    monitorJob?.cancel()
    modelJob = null
    poseJob = null
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
  }
}
