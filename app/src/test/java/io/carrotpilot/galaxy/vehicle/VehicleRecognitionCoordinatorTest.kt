package io.carrotpilot.galaxy.vehicle

import io.carrotpilot.galaxy.vehicle.fake.FakeCanIngestSource
import io.carrotpilot.galaxy.vehicle.fake.FakeScenario
import io.carrotpilot.galaxy.vehicle.fake.FakeUsbHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleRecognitionCoordinatorTest {
  @Test
  fun fakePipeline_reachesSafetyReady() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val coordinator = VehicleRecognitionCoordinator(
      scope = scope,
      usbHostManager = FakeUsbHostManager(scenario = FakeScenario.HAPPY_PATH),
      canIngestSource = FakeCanIngestSource(targetCar = "HYUNDAI_CASPER_EV", scenario = FakeScenario.HAPPY_PATH),
      fingerprintEngine = SimpleFingerprintEngine(minMatchesForIdentification = 3),
    )

    coordinator.start()
    var tries = 0
    while (tries < 50 && coordinator.state.value.stage != VehicleStage.SAFETY_READY) {
      delay(50)
      tries++
    }

    assertEquals(VehicleStage.SAFETY_READY, coordinator.state.value.stage)
    coordinator.stop()
    scope.cancel()
  }

  @Test
  fun fakePipeline_interfaceLoadFail_stopsBeforeSafetyReady() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val coordinator = VehicleRecognitionCoordinator(
      scope = scope,
      usbHostManager = FakeUsbHostManager(scenario = FakeScenario.INTERFACE_LOAD_FAIL),
      canIngestSource = FakeCanIngestSource(
        targetCar = "HYUNDAI_CASPER_EV",
        scenario = FakeScenario.INTERFACE_LOAD_FAIL,
      ),
      fingerprintEngine = SimpleFingerprintEngine(minMatchesForIdentification = 3),
      carInterfaceLoader = DefaultCarInterfaceLoader(failForCars = setOf("HYUNDAI_CASPER_EV")),
    )

    coordinator.start()
    var tries = 0
    while (tries < 60 && coordinator.state.value.error != VehicleErrorCode.INTERFACE_LOAD_FAIL) {
      delay(50)
      tries++
    }

    assertEquals(VehicleStage.CAR_IDENTIFIED, coordinator.state.value.stage)
    assertEquals(VehicleErrorCode.INTERFACE_LOAD_FAIL, coordinator.state.value.error)
    coordinator.stop()
    scope.cancel()
  }

  @Test
  fun fakePipeline_fingerprintTimeout_setsExpectedError() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val coordinator = VehicleRecognitionCoordinator(
      scope = scope,
      usbHostManager = FakeUsbHostManager(scenario = FakeScenario.FINGERPRINT_TIMEOUT),
      canIngestSource = FakeCanIngestSource(
        targetCar = "HYUNDAI_CASPER_EV",
        scenario = FakeScenario.FINGERPRINT_TIMEOUT,
      ),
      fingerprintEngine = SimpleFingerprintEngine(minMatchesForIdentification = 3),
    )

    coordinator.start()
    var tries = 0
    while (tries < 140 && coordinator.state.value.error != VehicleErrorCode.FINGERPRINT_TIMEOUT) {
      delay(50)
      tries++
    }

    assertEquals(VehicleStage.CAN_RX_STABLE, coordinator.state.value.stage)
    assertEquals(VehicleErrorCode.FINGERPRINT_TIMEOUT, coordinator.state.value.error)
    coordinator.stop()
    scope.cancel()
  }
}
