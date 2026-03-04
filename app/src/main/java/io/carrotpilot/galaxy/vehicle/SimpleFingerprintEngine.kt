package io.carrotpilot.galaxy.vehicle

class SimpleFingerprintEngine(
  private val signaturesByCar: Map<String, Set<CanSignature>> = FingerprintCatalog.signaturesByCar,
  private val minMatchesForIdentification: Int = 3,
) : FingerprintEngine {
  private val observed = LinkedHashSet<CanSignature>()

  override fun reset() {
    observed.clear()
  }

  override fun onFrame(frame: CanFrame): FingerprintProgress {
    observed.add(CanSignature(frame.address, frame.data.size))

    val matches = signaturesByCar.mapValues { (_, signatures) ->
      observed.intersect(signatures).size
    }

    val maxMatch = matches.maxByOrNull { it.value }
    val identified = if (maxMatch != null && maxMatch.value >= minMatchesForIdentification) {
      val tied = matches.filterValues { it == maxMatch.value }.keys
      if (tied.size == 1) maxMatch.key else null
    } else {
      null
    }

    val candidates = matches
      .filterValues { it > 0 }
      .keys
      .ifEmpty { signaturesByCar.keys }
      .toSet()

    return FingerprintProgress(
      candidates = candidates,
      identifiedCar = identified,
      observedSignatureCount = observed.size,
    )
  }
}

