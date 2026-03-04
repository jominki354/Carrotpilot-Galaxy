package io.carrotpilot.galaxy.vehicle

// AUTO-GENERATED FILE. DO NOT EDIT MANUALLY.
// Source: offline_porting/data/hyundai_seed_signatures.json
// Generated at UTC: 2026-03-03T14:32:35.030149+00:00
object FingerprintCatalogGenerated {
  val signaturesByCar: Map<String, Set<CanSignature>> = mapOf(
    "HYUNDAI_CASPER_EV" to setOf(
      CanSignature(address = 0x105, length = 8),
      CanSignature(address = 0x1A0, length = 8),
      CanSignature(address = 0x260, length = 8),
      CanSignature(address = 0x329, length = 8),
    ),
    "HYUNDAI_KONA_EV" to setOf(
      CanSignature(address = 0x140, length = 8),
      CanSignature(address = 0x153, length = 8),
      CanSignature(address = 0x340, length = 8),
      CanSignature(address = 0x130, length = 8),
      CanSignature(address = 0x160, length = 8),
      CanSignature(address = 0x164, length = 4),
      CanSignature(address = 0x220, length = 8),
      CanSignature(address = 0x371, length = 8),
      CanSignature(address = 0x372, length = 8),
      CanSignature(address = 0x2B0, length = 5),
      CanSignature(address = 0x251, length = 8),
      CanSignature(address = 0x386, length = 8),
      CanSignature(address = 0x387, length = 8),
      CanSignature(address = 0x38D, length = 8),
      CanSignature(address = 0x420, length = 8),
      CanSignature(address = 0x421, length = 8),
      CanSignature(address = 0x381, length = 8),
      CanSignature(address = 0x389, length = 8),
      CanSignature(address = 0x394, length = 8),
      CanSignature(address = 0x470, length = 8),
      CanSignature(address = 0x47F, length = 6),
      CanSignature(address = 0x4F1, length = 4),
      CanSignature(address = 0x436, length = 4),
      CanSignature(address = 0x490, length = 7),
    ),
    "KIA_EV6" to setOf(
      CanSignature(address = 0x50, length = 16),
      CanSignature(address = 0x51, length = 32),
      CanSignature(address = 0x202, length = 32),
      CanSignature(address = 0x241, length = 24),
      CanSignature(address = 0x242, length = 24),
      CanSignature(address = 0x243, length = 24),
      CanSignature(address = 0x244, length = 24),
      CanSignature(address = 0x245, length = 24),
      CanSignature(address = 0x246, length = 24),
      CanSignature(address = 0x247, length = 24),
      CanSignature(address = 0x248, length = 24),
      CanSignature(address = 0x249, length = 24),
      CanSignature(address = 0x24A, length = 24),
      CanSignature(address = 0x24B, length = 24),
      CanSignature(address = 0x24C, length = 24),
      CanSignature(address = 0x24D, length = 24),
      CanSignature(address = 0x24E, length = 24),
      CanSignature(address = 0x24F, length = 24),
      CanSignature(address = 0x270, length = 32),
      CanSignature(address = 0x271, length = 32),
      CanSignature(address = 0x272, length = 32),
      CanSignature(address = 0x273, length = 32),
      CanSignature(address = 0x274, length = 32),
      CanSignature(address = 0x275, length = 32),
    ),
  )

  val metadataByCar: Map<String, FingerprintProfileMetadata> = mapOf(
    "HYUNDAI_CASPER_EV" to FingerprintProfileMetadata(
      source = "temporary_fallback",
      note = "no openpilot FW_VERSIONS and no public route in snapshot",
      aliasCandidate = "HYUNDAI_CASPER",
      routeRefs = emptyList(),
    ),
    "HYUNDAI_KONA_EV" to FingerprintProfileMetadata(
      source = "openpilotci_route",
      routeRefs = listOf(
        "efc48acf44b1e64d|2021-05-28--21-05-04/0",
        "f90d3cd06caeb6fa|2023-09-06--17-15-47/0",
    ),
    ),
    "KIA_EV6" to FingerprintProfileMetadata(
      source = "openpilotci_route",
      routeRefs = listOf(
        "9b25e8c1484a1b67|2023-04-13--10-41-45/0",
    ),
      routeErrors = listOf(
        "68d6a96e703c00c9|2022-09-10--16-09-39: Could not extract route signatures: route=68d6a96e703c00c9|2022-09-10--16-09-39: No downloadable route log found: route=68d6a96e703c00c9|2022-09-10--16-09-39, segment=24",
    ),
    ),
  )
}
