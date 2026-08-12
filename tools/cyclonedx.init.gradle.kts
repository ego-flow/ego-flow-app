import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.gradle.CyclonedxPlugin
import org.cyclonedx.model.Component

initscript {
  repositories {
    gradlePluginPortal()
  }
  dependencies {
    classpath("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.3.0")
  }
}

allprojects {
  group = "io.egoflow"
  version = "0.0.1"
  apply<CyclonedxPlugin>()

  tasks.withType<CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    skipConfigs.set(listOf(".*[Tt]est.*", ".*[Dd]ebug.*"))
    componentName.set(if (project.name == "app") "ego-flow-app" else "ego-flow-${project.name}")
    componentVersion.set("0.0.1")
    projectType.set(if (project.name == "app") Component.Type.APPLICATION else Component.Type.LIBRARY)
    schemaVersion.set(Version.VERSION_16)
    includeBuildEnvironment.set(false)
    includeLicenseText.set(false)
    xmlOutput.unsetConvention()
  }
}
