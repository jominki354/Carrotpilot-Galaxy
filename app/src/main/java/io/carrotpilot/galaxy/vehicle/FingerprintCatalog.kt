package io.carrotpilot.galaxy.vehicle

data class FingerprintProfileMetadata(
  val source: String,
  val note: String? = null,
  val aliasCandidate: String? = null,
  val routeRefs: List<String> = emptyList(),
  val routeErrors: List<String> = emptyList(),
)

object FingerprintCatalog {
  val signaturesByCar: Map<String, Set<CanSignature>> = FingerprintCatalogGenerated.signaturesByCar
  val metadataByCar: Map<String, FingerprintProfileMetadata> = FingerprintCatalogGenerated.metadataByCar
}

