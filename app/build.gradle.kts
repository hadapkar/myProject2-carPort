import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CarPort's own release key. Keep it stable: installing a CarPort build signed with a
// different key forces an uninstall, which deletes the key CarPort signs patched apps with.
// keystore/keystore.properties is git-ignored; see README "Signing CarPort itself".
val releaseKeystore = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// Single source of truth: carport.version in gradle.properties. versionCode is derived so the
// two can never disagree: MAJOR * 10000 + MINOR * 100 + PATCH (0.1.0 -> 100, 1.2.3 -> 10203).
val appVersionName: String = providers.gradleProperty("carport.version").get()
val appVersionCode: Int = run {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersionName)
        ?: error("carport.version must be MAJOR.MINOR.PATCH, got '$appVersionName'")
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    require(minor < 100 && patch < 100) { "carport.version minor and patch must be below 100" }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "dev.hadapkar.carport"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hadapkar.carport"
        // Android Auto only projects ordinary activities from Android 15 on.
        minSdk = 35
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // Public GitHub repository whose Releases CarPort updates itself from (owner/name).
        val updateRepo = providers.gradleProperty("carport.updateRepo").getOrElse("hadapkar/myProject2-carPort")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (!releaseKeystore.isEmpty) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // ARSCLib, apksig and Bouncy Castle use reflection; shrinking is not worth the risk.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":patcher"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
