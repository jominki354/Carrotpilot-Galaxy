package io.carrotpilot.galaxy.driving

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class DrivingCorePipeline(
  private val scope: CoroutineScope,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  private companion object {
    const val PLANNER_INTERVAL_MS = 50L
    const val CONTROL_INTERVAL_MS = 10L
  }

  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<DrivingCoreState> = _state.asStateFlow()

  private var plannerJob: Job? = null
  private var controlJob: Job? = null
  private var monitorJob: Job? = null

  @Volatile private var running = false
  @Volatile private var startedAtMs = 0L
  @Volatile private var plannerTicks = 0L
  @Volatile private var controlTicks = 0L
  @Volatile private var stage = DrivingCoreStage.STOPPED
  @Volatile private var inputs = DrivingCoreInputSnapshot()

  fun updateInputs(snapshot: DrivingCoreInputSnapshot) {
    inputs = snapshot
  }

  suspend fun start() {
    stop()
    resetCounters()
    running = true
    startedAtMs = nowMs()
    stage = DrivingCoreStage.DISABLED
    publishState()

    plannerJob = scope.launch(Dispatchers.Default) {
      while (isActive && running) {
        delay(PLANNER_INTERVAL_MS)
        if (shouldRunLoops(stage, inputs)) {
          plannerTicks += 1
        }
      }
    }

    controlJob = scope.launch(Dispatchers.Default) {
      while (isActive && running) {
        delay(CONTROL_INTERVAL_MS)
        if (shouldRunLoops(stage, inputs)) {
          controlTicks += 1
        }
      }
    }

    monitorJob = scope.launch(Dispatchers.Default) {
      while (isActive && running) {
        delay(100L)
        advanceState()
        publishState()
      }
    }
  }

  suspend fun stop() {
    running = false
    plannerJob?.cancel()
    controlJob?.cancel()
    monitorJob?.cancel()
    plannerJob = null
    controlJob = null
    monitorJob = null
    stage = DrivingCoreStage.STOPPED
    publishState()
  }

  fun reset() {
    running = false
    plannerJob?.cancel()
    controlJob?.cancel()
    monitorJob?.cancel()
    plannerJob = null
    controlJob = null
    monitorJob = null
    resetCounters()
    stage = DrivingCoreStage.STOPPED
    _state.value = initialState()
  }

  private fun advanceState() {
    if (!running) {
      stage = DrivingCoreStage.STOPPED
      return
    }

    val snapshot = inputs
    if (snapshot.userDisable || snapshot.immediateDisable) {
      stage = DrivingCoreStage.DISABLED
      return
    }

    stage = when (stage) {
      DrivingCoreStage.STOPPED -> DrivingCoreStage.DISABLED

      DrivingCoreStage.DISABLED -> when {
        !snapshot.enableRequested -> DrivingCoreStage.DISABLED
        snapshot.preEnable -> DrivingCoreStage.PRE_ENABLED
        !snapshot.noEntry -> DrivingCoreStage.ENABLED
        else -> DrivingCoreStage.DISABLED
      }

      DrivingCoreStage.PRE_ENABLED -> when {
        !snapshot.enableRequested -> DrivingCoreStage.DISABLED
        snapshot.preEnable -> DrivingCoreStage.PRE_ENABLED
        snapshot.noEntry -> DrivingCoreStage.PRE_ENABLED
        else -> DrivingCoreStage.ENABLED
      }

      DrivingCoreStage.ENABLED -> when {
        !snapshot.enableRequested -> DrivingCoreStage.DISABLED
        snapshot.noEntry -> DrivingCoreStage.DISABLED
        snapshot.softDisable -> DrivingCoreStage.SOFT_DISABLING
        snapshot.overrideLateral || snapshot.overrideLongitudinal -> DrivingCoreStage.OVERRIDING
        else -> DrivingCoreStage.ENABLED
      }

      DrivingCoreStage.SOFT_DISABLING -> when {
        !snapshot.enableRequested -> DrivingCoreStage.DISABLED
        snapshot.userDisable || snapshot.immediateDisable -> DrivingCoreStage.DISABLED
        snapshot.softDisable -> DrivingCoreStage.SOFT_DISABLING
        snapshot.noEntry -> DrivingCoreStage.DISABLED
        else -> DrivingCoreStage.ENABLED
      }

      DrivingCoreStage.OVERRIDING -> when {
        !snapshot.enableRequested -> DrivingCoreStage.DISABLED
        snapshot.userDisable || snapshot.immediateDisable -> DrivingCoreStage.DISABLED
        snapshot.overrideLateral || snapshot.overrideLongitudinal -> DrivingCoreStage.OVERRIDING
        snapshot.noEntry -> DrivingCoreStage.DISABLED
        else -> DrivingCoreStage.ENABLED
      }
    }
  }

  private fun publishState() {
    val snapshot = inputs
    val elapsedMs = max(1L, nowMs() - startedAtMs)
    val plannerHz = plannerTicks * 1000.0 / elapsedMs
    val controlHz = controlTicks * 1000.0 / elapsedMs
    val eligible = isControlEligible(snapshot)
    val active = eligible && (
      stage == DrivingCoreStage.ENABLED ||
        stage == DrivingCoreStage.SOFT_DISABLING ||
        stage == DrivingCoreStage.OVERRIDING
      )

    _state.value = DrivingCoreState(
      stage = stage,
      error = resolveError(snapshot),
      latActive = active,
      longActive = active,
      sendcanAllowed = active && stage == DrivingCoreStage.ENABLED,
      plannerHz = plannerHz,
      controlHz = controlHz,
      plannerTicks = plannerTicks,
      controlTicks = controlTicks,
      updatedAtMs = nowMs(),
    )
  }

  private fun shouldRunLoops(
    stage: DrivingCoreStage,
    snapshot: DrivingCoreInputSnapshot,
  ): Boolean {
    return isControlEligible(snapshot) && (
      stage == DrivingCoreStage.ENABLED ||
        stage == DrivingCoreStage.SOFT_DISABLING ||
        stage == DrivingCoreStage.OVERRIDING
      )
  }

  private fun isControlEligible(snapshot: DrivingCoreInputSnapshot): Boolean {
    return snapshot.carRecognized &&
      snapshot.carParamsValid &&
      snapshot.safetyReady &&
      snapshot.modelFresh &&
      snapshot.vehicleFresh &&
      snapshot.calibrationValid &&
      !snapshot.noEntry &&
      !snapshot.immediateDisable
  }

  private fun resolveError(snapshot: DrivingCoreInputSnapshot): DrivingCoreErrorCode {
    if (!running || stage == DrivingCoreStage.STOPPED) return DrivingCoreErrorCode.NONE
    return when {
      snapshot.immediateDisable -> DrivingCoreErrorCode.IMMEDIATE_DISABLE
      snapshot.softDisable -> DrivingCoreErrorCode.SOFT_DISABLE
      snapshot.noEntry -> DrivingCoreErrorCode.NO_ENTRY
      !snapshot.safetyReady -> DrivingCoreErrorCode.SAFETY_NOT_READY
      !snapshot.modelFresh -> DrivingCoreErrorCode.MODEL_NOT_READY
      !snapshot.vehicleFresh -> DrivingCoreErrorCode.VEHICLE_NOT_READY
      else -> DrivingCoreErrorCode.NONE
    }
  }

  private fun initialState(): DrivingCoreState {
    return DrivingCoreState(updatedAtMs = nowMs())
  }

  private fun resetCounters() {
    startedAtMs = 0L
    plannerTicks = 0L
    controlTicks = 0L
  }
}
