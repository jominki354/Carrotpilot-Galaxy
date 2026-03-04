package io.carrotpilot.galaxy.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ModelRuntimeCameraPipelineTest {
  private class CapturingInferenceEngine : ModelInferenceEngine {
    val lastFrame = AtomicReference<ModelInputFrame?>(null)
    override val backendName: String = "CAPTURE"
    override fun initialize(): Boolean = true
    override fun run(
      frameTimestampMs: Long,
      inputFrame: ModelInputFrame?,
    ): ModelInferenceResult {
      lastFrame.set(inputFrame)
      return ModelInferenceResult(success = true, latencyMs = 0.1, outputsProduced = 1)
    }
  }

  @Test
  fun permissionDenied_setsCameraPermissionDeniedError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = false)

    assertEquals(ModelRuntimeStage.ERROR, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.CAMERA_PERMISSION_DENIED, pipeline.state.value.error)
    assertEquals("ONNX_PLACEHOLDER", pipeline.state.value.inferenceBackend)
    assertTrue(!pipeline.state.value.inferenceReady)
    assertEquals(0L, pipeline.state.value.inferenceOutputCount)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    pipeline.stopSession()
    scope.cancel()
  }

  @Test
  fun noFramesAfterStart_keepsCameraReady() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    delay(300L)

    assertEquals(ModelRuntimeStage.CAMERA_READY, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)
    assertEquals("ONNX_PLACEHOLDER", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceReady)
    assertEquals(0L, pipeline.state.value.inferenceOutputCount)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    pipeline.stopSession()
    scope.cancel()
  }

  @Test
  fun stopSession_keepsLastMetrics_andClearsError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    val frame = sampleFrame()
    repeat(20) {
      pipeline.onCameraFrame(inputFrame = frame)
      delay(20L)
    }
    delay(300L)
    pipeline.stopSession()

    assertEquals(ModelRuntimeStage.STOPPED, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)
    assertTrue(pipeline.state.value.modelFrameCount > 0)
    assertEquals("ONNX_PLACEHOLDER", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceOutputCount > 0L)
    assertTrue(pipeline.state.value.inferenceLatencyMsP50 > 0.0)
    assertTrue(pipeline.state.value.inferenceLatencyMsP95 >= pipeline.state.value.inferenceLatencyMsP50)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    scope.cancel()
  }

  @Test
  fun shortFrameGap_doesNotTriggerTimeoutError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    val frame = sampleFrame()
    repeat(20) {
      pipeline.onCameraFrame(inputFrame = frame)
      delay(20L)
    }

    // A single short stall should not trip timeout due to streak threshold.
    delay(700L)

    assertTrue(
      pipeline.state.value.stage == ModelRuntimeStage.CAMERA_READY ||
        pipeline.state.value.stage == ModelRuntimeStage.MODEL_STREAMING ||
        pipeline.state.value.stage == ModelRuntimeStage.STABLE,
    )
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)

    pipeline.stopSession()
    scope.cancel()
  }

  @Test
  fun foreignTimestampDomain_onCameraFrame_doesNotTriggerTimeout() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    val frame = sampleFrame()
    repeat(50) {
      // Simulate callback providing a timestamp from another time domain.
      pipeline.onCameraFrame(timestampMs = 123L, inputFrame = frame)
      delay(20L)
    }

    assertTrue(
      pipeline.state.value.stage == ModelRuntimeStage.MODEL_STREAMING ||
        pipeline.state.value.stage == ModelRuntimeStage.STABLE,
    )
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)

    pipeline.stopSession()
    scope.cancel()
  }

  @Test
  fun markPermissionDenied_setsExpectedErrorState() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    pipeline.markPermissionDenied()

    assertEquals(ModelRuntimeStage.ERROR, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.CAMERA_PERMISSION_DENIED, pipeline.state.value.error)
    assertEquals("ONNX_PLACEHOLDER", pipeline.state.value.inferenceBackend)
    assertTrue(!pipeline.state.value.inferenceReady)
    assertEquals(0L, pipeline.state.value.modelFrameCount)
    assertEquals(0L, pipeline.state.value.inferenceOutputCount)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    scope.cancel()
  }

  @Test
  fun onCameraFrame_withImage_passesFrameIntoInferenceEngine() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val engine = CapturingInferenceEngine()
    val pipeline = ModelRuntimeCameraPipeline(
      scope = scope,
      inferenceEngine = engine,
    )

    pipeline.startSession(permissionGranted = true)
    val frame = ModelInputFrame(
      lumaBytes = ByteArray(16) { index -> index.toByte() },
      width = 4,
      height = 4,
    )
    pipeline.onCameraFrame(inputFrame = frame)
    var attempts = 0
    while (attempts < 20 && engine.lastFrame.get() == null) {
      delay(20L)
      attempts += 1
    }

    val captured = engine.lastFrame.get()
    assertTrue(captured != null)
    assertEquals(4, captured?.width)
    assertEquals(4, captured?.height)
    assertEquals(16, captured?.lumaBytes?.size)

    pipeline.stopSession()
    scope.cancel()
  }

  private fun sampleFrame(): ModelInputFrame {
    return ModelInputFrame(
      lumaBytes = ByteArray(16) { index -> index.toByte() },
      width = 4,
      height = 4,
    )
  }
}
