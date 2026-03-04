package io.carrotpilot.galaxy.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleRecognitionStateMachineTest {
  private val machine = VehicleRecognitionStateMachine(
    policy = VehicleRecognitionPolicy(pandaConnectMaxRetries = 3),
    now = { 1000L },
  )

  @Test
  fun happyPath_reachesSafetyReady() {
    var state = machine.initialState()
    state = machine.transition(state, VehicleEvent.UsbPermissionResult(granted = true))
    state = machine.transition(state, VehicleEvent.PandaConnected)
    state = machine.transition(state, VehicleEvent.CanRxStable)
    state = machine.transition(state, VehicleEvent.StartFingerprinting)
    state = machine.transition(state, VehicleEvent.FingerprintIdentified("HYUNDAI_CASPER_EV"))
    state = machine.transition(
      state,
      VehicleEvent.CarParamsPublished(
        CarParams(
          carFingerprint = "HYUNDAI_CASPER_EV",
          safetyModel = "hyundai",
          safetyParam = 1,
        ),
      ),
    )
    state = machine.transition(state, VehicleEvent.SafetyReady)

    assertEquals(VehicleStage.SAFETY_READY, state.stage)
    assertEquals("HYUNDAI_CASPER_EV", state.identifiedCarFingerprint)
    assertEquals(1, state.carParams?.safetyParam)
  }

  @Test
  fun pandaConnectFailed_threeTimes_movesToDisconnected() {
    var state = machine.initialState()
    state = machine.transition(state, VehicleEvent.UsbPermissionResult(granted = true))
    state = machine.transition(state, VehicleEvent.PandaConnectFailed)
    state = machine.transition(state, VehicleEvent.PandaConnectFailed)
    state = machine.transition(state, VehicleEvent.PandaConnectFailed)

    assertEquals(VehicleStage.DISCONNECTED, state.stage)
    assertEquals(VehicleErrorCode.PANDA_CONNECT_FAIL, state.error)
    assertEquals(3, state.pandaConnectRetries)
  }

  @Test
  fun disconnect_clearsIdentifiedCar() {
    var state = machine.initialState()
    state = machine.transition(state, VehicleEvent.UsbPermissionResult(granted = true))
    state = machine.transition(state, VehicleEvent.PandaConnected)
    state = machine.transition(state, VehicleEvent.CanRxStable)
    state = machine.transition(state, VehicleEvent.StartFingerprinting)
    state = machine.transition(state, VehicleEvent.FingerprintIdentified("HYUNDAI_CASPER_EV"))
    state = machine.transition(state, VehicleEvent.Disconnect)

    assertEquals(VehicleStage.DISCONNECTED, state.stage)
    assertNull(state.identifiedCarFingerprint)
  }
}
