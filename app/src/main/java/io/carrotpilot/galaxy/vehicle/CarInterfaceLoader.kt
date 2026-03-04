package io.carrotpilot.galaxy.vehicle

sealed interface CarInterfaceLoadResult {
  data class Success(val carParams: CarParams) : CarInterfaceLoadResult
  data class Failure(val reason: String) : CarInterfaceLoadResult
}

interface CarInterfaceLoader {
  fun load(carFingerprint: String): CarInterfaceLoadResult
}

class DefaultCarInterfaceLoader(
  private val failForCars: Set<String> = emptySet(),
) : CarInterfaceLoader {
  override fun load(carFingerprint: String): CarInterfaceLoadResult {
    if (carFingerprint in failForCars) {
      return CarInterfaceLoadResult.Failure("interface disabled for test scenario")
    }

    val safetyParam = when (carFingerprint) {
      "HYUNDAI_CASPER_EV" -> 1
      "HYUNDAI_KONA_EV" -> 2
      "KIA_EV6" -> 3
      else -> return CarInterfaceLoadResult.Failure("unsupported car fingerprint: $carFingerprint")
    }

    return CarInterfaceLoadResult.Success(
      CarParams(
        carFingerprint = carFingerprint,
        safetyModel = "hyundai",
        safetyParam = safetyParam,
      ),
    )
  }
}
