package io.egoflow.app.extentos

import com.extentos.glasses.core.Resolution
import io.egoflow.app.settings.GlassesVideoQuality

internal fun GlassesVideoQuality.toExtentosResolution(): Resolution =
    when (this) {
      GlassesVideoQuality.LOW -> Resolution.LOW
      GlassesVideoQuality.MEDIUM -> Resolution.MEDIUM
      GlassesVideoQuality.HIGH -> Resolution.HIGH
    }
