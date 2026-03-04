package io.carrotpilot.galaxy.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimeMockPipelineTest {
  @Test
  fun happyPath_reachesStableWithExpectedCadence() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeMockPipeline(scope = scope)

    pipeline.start(ModelRuntimeMockScenario.HAPPY_PATH)
    var tries = 0
    while (tries < 80 && pipeline.state.value.stage != ModelRuntimeStage.STABLE) {
      delay(100L)
      tries++
    }

    assertEquals(ModelRuntimeStage.STABLE, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)
    assertTrue(pipeline.state.value.modelHz in 16.0..24.5)
    assertEquals("MOCK", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceReady)
    assertTrue(pipeline.state.value.inferenceOutputCount > 0L)
    assertTrue(pipeline.state.value.inferenceLatencyMsP50 > 0.0)
    assertTrue(pipeline.state.value.inferenceLatencyMsP95 >= pipeline.state.value.inferenceLatencyMsP50)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun modelTimeout_setsExpectedError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeMockPipeline(scope = scope)

    pipeline.start(ModelRuntimeMockScenario.MODEL_TIMEOUT)
    var tries = 0
    while (tries < 80 && pipeline.state.value.error != ModelRuntimeErrorCode.MODEL_TIMEOUT) {
      delay(100L)
      tries++
    }

    assertEquals(ModelRuntimeStage.ERROR, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.MODEL_TIMEOUT, pipeline.state.value.error)
    assertEquals("MOCK", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceReady)
    assertTrue(pipeline.state.value.inferenceOutputCount > 0L)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun poseTimeout_setsExpectedError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeMockPipeline(scope = scope)

    pipeline.start(ModelRuntimeMockScenario.POSE_TIMEOUT)
    var tries = 0
    while (tries < 80 && pipeline.state.value.error != ModelRuntimeErrorCode.POSE_TIMEOUT) {
      delay(100L)
      tries++
    }

    assertEquals(ModelRuntimeStage.ERROR, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.POSE_TIMEOUT, pipeline.state.value.error)
    assertEquals("MOCK", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceReady)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun stop_keepsLastMetrics_andClearsError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = ModelRuntimeMockPipeline(scope = scope)

    pipeline.start(ModelRuntimeMockScenario.HAPPY_PATH)
    delay(1_200L)
    pipeline.stop()

    assertEquals(ModelRuntimeStage.STOPPED, pipeline.state.value.stage)
    assertEquals(ModelRuntimeErrorCode.NONE, pipeline.state.value.error)
    assertTrue(pipeline.state.value.modelFrameCount > 0)
    assertEquals("MOCK", pipeline.state.value.inferenceBackend)
    assertTrue(pipeline.state.value.inferenceOutputCount > 0L)
    assertEquals(0L, pipeline.state.value.inferenceFailures)
    assertEquals("-", pipeline.state.value.inferenceLastFailure)

    scope.cancel()
  }
}
