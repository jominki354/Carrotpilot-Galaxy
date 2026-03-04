package io.carrotpilot.galaxy.vehicle.fake

import io.carrotpilot.galaxy.vehicle.CanFrame
import io.carrotpilot.galaxy.vehicle.CanIngestSource
import io.carrotpilot.galaxy.vehicle.FingerprintCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class FakeCanIngestSource(
  private val targetCar: String = "HYUNDAI_CASPER_EV",
  private val scenario: FakeScenario = FakeScenario.HAPPY_PATH,
) : CanIngestSource {
  private val _frames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 128)
  override val frames: Flow<CanFrame> = _frames.asSharedFlow()

  private val scope = CoroutineScope(Dispatchers.Default)
  private var job: Job? = null

  override suspend fun start() {
    if (job != null) return
    val targetSignatures = FingerprintCatalog.signaturesByCar[targetCar].orEmpty().toList()
    if (targetSignatures.isEmpty()) return

    val allSignatures = FingerprintCatalog.signaturesByCar.values.flatten().toSet()
    val unknownSignaturePool = (0x500..0x54F)
      .map { address -> io.carrotpilot.galaxy.vehicle.CanSignature(address, 8) }
      .filterNot { it in allSignatures }
      .ifEmpty { listOf(io.carrotpilot.galaxy.vehicle.CanSignature(0x6AA, 8)) }

    job = scope.launch {
      when (scenario) {
        FakeScenario.HAPPY_PATH,
        FakeScenario.INTERFACE_LOAD_FAIL,
        -> {
          while (isActive) {
            emitRandomSignature(targetSignatures)
            delay(20) // ~50Hz fake stream
          }
        }

        FakeScenario.FINGERPRINT_TIMEOUT -> {
          while (isActive) {
            emitRandomSignature(unknownSignaturePool)
            delay(20)
          }
        }

        FakeScenario.CAN_TIMEOUT -> {
          repeat(10) {
            if (!isActive) return@launch
            emitRandomSignature(unknownSignaturePool)
            delay(20)
          }
          // Emit enough frames to enter fingerprinting, then stop stream to trigger CAN timeout.
          while (isActive) {
            delay(200)
          }
        }

        FakeScenario.USB_PERMISSION_DENIED,
        FakeScenario.PANDA_CONNECT_FAIL,
        -> {
          while (isActive) {
            delay(200)
          }
        }
      }
    }
  }

  override suspend fun stop() {
    job?.cancel()
    job = null
  }

  private suspend fun emitRandomSignature(signatures: List<io.carrotpilot.galaxy.vehicle.CanSignature>) {
    val sig = signatures.random()
    _frames.emit(
      CanFrame(
        timestampNs = System.nanoTime(),
        bus = 0,
        address = sig.address,
        data = Random.nextBytes(sig.length),
        isCanFd = false,
      ),
    )
  }
}
