package io.carrotpilot.galaxy.vehicle

data class FingerprintProgress(
  val candidates: Set<String>,
  val identifiedCar: String?,
  val observedSignatureCount: Int,
)

interface FingerprintEngine {
  fun reset()
  fun onFrame(frame: CanFrame): FingerprintProgress
}

