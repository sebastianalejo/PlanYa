import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Configuración local ignorada por Git. Nunca imprimir la clave al compilar.
val placesSecrets = Properties().apply {
    val source = rootProject.file("secrets.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val placesKey = placesSecrets.getProperty("GOOGLE_PLACES_API_KEY", "").trim()
val placesEnabled = placesSecrets.getProperty("GOOGLE_PLACES_ENABLED", "false").toBoolean()
val mapsEnabled = placesSecrets.getProperty("GOOGLE_MAPS_ENABLED", "false").toBoolean()

android {
    namespace = "com.example.planya"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.planya"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.example.planya"
        buildConfigField("String", "GOOGLE_PLACES_API_KEY", "\"${placesKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "GOOGLE_PLACES_ENABLED", placesEnabled.toString())
        buildConfigField("boolean", "GOOGLE_MAPS_ENABLED", (mapsEnabled && placesKey.isNotBlank()).toString())
        resValue("string", "google_maps_key", placesKey)
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.google.maps.compose)
    implementation(libs.appauth)
    implementation(libs.google.places)
    // Recursos de vistas que utiliza internamente Places; no cambia el tema Compose.
    implementation(libs.google.material.views)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
}
