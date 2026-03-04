package io.carrotpilot.galaxy.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimeCameraPipelineTest {
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

    pipeline.stopSession()
    scope.cancel()
  }

  @Test
  fun stopSession_keepsLastMetrics_andClearsError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    repeat(20) {
      pipeline.onCameraFrame()
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

    scope.cancel()
  }

  @Test
  fun shortFrameGap_doesNotTriggerTimeoutError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeCameraPipeline(scope = scope)

    pipeline.startSession(permissionGranted = true)
    repeat(20) {
      pipeline.onCameraFrame()
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
    repeat(50) {
      // Simulate callback providing a timestamp from another time domain.
      pipeline.onCameraFrame(timestampMs = 123L)
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

    scope.cancel()
  }
}
