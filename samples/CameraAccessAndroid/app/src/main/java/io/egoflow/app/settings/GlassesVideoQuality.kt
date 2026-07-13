package io.egoflow.app.settings

enum class GlassesVideoQuality {
  LOW,
  MEDIUM,
  HIGH,
  ;

  companion object {
    fun fromPreferenceValue(value: String?): GlassesVideoQuality =
        entries.firstOrNull { it.name == value } ?: MEDIUM
  }
}
