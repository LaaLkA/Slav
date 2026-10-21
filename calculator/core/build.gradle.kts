plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    // The model is @Serializable and BigDecimalSerializer is public, so consumers see the library.
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
