package io.carrotpilot.galaxy.driving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingCorePipelineTest {
  @Test
  fun enableWithReadyInputs_reachesEnabledAndActivatesControl() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = DrivingCorePipeline(scope = scope)
    pipeline.updateInputs(
      readySnapshot(enableRequested = true),
    )

    pipeline.start()
    delay(250L)

    assertEquals(DrivingCoreStage.ENABLED, pipeline.state.value.stage)
    assertEquals(DrivingCoreErrorCode.NONE, pipeline.state.value.error)
    assertTrue(pipeline.state.value.latActive)
    assertTrue(pipeline.state.value.longActive)
    assertTrue(pipeline.state.value.sendcanAllowed)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun noEntry_preventsEnableTransition() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = DrivingCorePipeline(scope = scope)
    pipeline.updateInputs(
      readySnapshot(
        enableRequested = true,
        noEntry = true,
      ),
    )

    pipeline.start()
    delay(250L)

    assertEquals(DrivingCoreStage.DISABLED, pipeline.state.value.stage)
    assertEquals(DrivingCoreErrorCode.NO_ENTRY, pipeline.state.value.error)
    assertTrue(!pipeline.state.value.sendcanAllowed)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun immediateDisable_forcesDisabled() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = DrivingCorePipeline(scope = scope)
    pipeline.updateInputs(readySnapshot(enableRequested = true))

    pipeline.start()
    delay(150L)
    pipeline.updateInputs(
      readySnapshot(
        enableRequested = true,
        immediateDisable = true,
      ),
    )
    delay(200L)

    assertEquals(DrivingCoreStage.DISABLED, pipeline.state.value.stage)
    assertEquals(DrivingCoreErrorCode.IMMEDIATE_DISABLE, pipeline.state.value.error)
    assertTrue(!pipeline.state.value.sendcanAllowed)

    pipeline.stop()
    scope.cancel()
  }

  @Test
  fun overrideState_transitionsBackToEnabled() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val pipeline = DrivingCorePipeline(scope = scope)
    pipeline.updateInputs(readySnapshot(enableRequested = true))

    pipeline.start()
    delay(150L)
    pipeline.updateInputs(
      readySnapshot(
        enableRequested = true,
        overrideLateral = true,
      ),
    )
    delay(200L)
    assertEquals(DrivingCoreStage.OVERRIDING, pipeline.state.value.stage)

    pipeline.updateInputs(readySnapshot(enableRequested = true))
    delay(200L)
    assertEquals(DrivingCoreStage.ENABLED, pipeline.state.value.stage)

    pipeline.stop()
    scope.cancel()
  }

  private fun readySnapshot(
    enableRequested: Boolean,
    noEntry: Boolean = false,
    immediateDisable: Boolean = false,
    overrideLateral: Boolean = false,
  ): DrivingCoreInputSnapshot {
    return DrivingCoreInputSnapshot(
      enableRequested = enableRequested,
      noEntry = noEntry,
      immediateDisable = immediateDisable,
      overrideLateral = overrideLateral,
      carRecognized = true,
      carParamsValid = true,
      safetyReady = true,
      modelFresh = true,
      vehicleFresh = true,
      calibrationValid = true,
    )
  }
}
