plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.arsclib)
    api(libs.apksig)
    implementation(libs.bouncycastle.pkix)
    testImplementation(libs.junit)
}

tasks.test {
    // Optional real-world fixture: -PtestApk=/path/to/any.apk
    providers.gradleProperty("testApk").orNull?.let { systemProperty("carport.testApk", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
