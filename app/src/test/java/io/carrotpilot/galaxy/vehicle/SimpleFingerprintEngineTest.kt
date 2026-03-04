package io.carrotpilot.galaxy.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleFingerprintEngineTest {
  @Test
  fun identifiesCasperWithKnownSignatures() {
    val engine = SimpleFingerprintEngine(minMatchesForIdentification = 3)
    val casper = FingerprintCatalog.signaturesByCar.getValue("HYUNDAI_CASPER_EV").toList()

    var identified: String? = null
    for (i in 0 until 3) {
      val sig = casper[i]
      val progress = engine.onFrame(
        CanFrame(
          timestampNs = 1L + i,
          bus = 0,
          address = sig.address,
          data = ByteArray(sig.length),
        ),
      )
      identified = progress.identifiedCar
    }

    assertNotNull(identified)
    assertEquals("HYUNDAI_CASPER_EV", identified)
  }

  @Test
  fun identifiesKonaEvWithRouteSeedSignatures() {
    val engine = SimpleFingerprintEngine(minMatchesForIdentification = 3)
    val kona = FingerprintCatalog.signaturesByCar.getValue("HYUNDAI_KONA_EV").toList()

    var identified: String? = null
    for (i in 0 until 3) {
      val sig = kona[i]
      val progress = engine.onFrame(
        CanFrame(
          timestampNs = 100L + i,
          bus = 0,
          address = sig.address,
          data = ByteArray(sig.length),
        ),
      )
      identified = progress.identifiedCar
    }

    assertEquals("HYUNDAI_KONA_EV", identified)
  }

  @Test
  fun casperFallbackMetadataIsDeclared() {
    val meta = FingerprintCatalog.metadataByCar.getValue("HYUNDAI_CASPER_EV")
    assertEquals("temporary_fallback", meta.source)
    assertEquals("HYUNDAI_CASPER", meta.aliasCandidate)
    assertTrue(meta.note?.lowercase()?.contains("fw_versions") == true)
  }
}
