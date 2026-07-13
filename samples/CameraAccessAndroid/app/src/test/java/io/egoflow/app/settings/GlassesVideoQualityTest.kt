package io.egoflow.app.settings

import com.extentos.glasses.core.Resolution
import io.egoflow.app.extentos.toExtentosResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesVideoQualityTest {
  @Test
  fun `restores every persisted quality name`() {
    assertEquals(GlassesVideoQuality.LOW, GlassesVideoQuality.fromPreferenceValue("LOW"))
    assertEquals(GlassesVideoQuality.MEDIUM, GlassesVideoQuality.fromPreferenceValue("MEDIUM"))
    assertEquals(GlassesVideoQuality.HIGH, GlassesVideoQuality.fromPreferenceValue("HIGH"))
  }

  @Test
  fun `falls back to medium for a missing value`() {
    assertEquals(GlassesVideoQuality.MEDIUM, GlassesVideoQuality.fromPreferenceValue(null))
  }

  @Test
  fun `falls back to medium for an unknown value`() {
    assertEquals(GlassesVideoQuality.MEDIUM, GlassesVideoQuality.fromPreferenceValue("ULTRA"))
  }

  @Test
  fun `maps every quality to the matching Extentos resolution`() {
    assertEquals(Resolution.LOW, GlassesVideoQuality.LOW.toExtentosResolution())
    assertEquals(Resolution.MEDIUM, GlassesVideoQuality.MEDIUM.toExtentosResolution())
    assertEquals(Resolution.HIGH, GlassesVideoQuality.HIGH.toExtentosResolution())
  }
}
