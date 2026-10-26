plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.mcardosor.candango"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mcardosor.candango"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    // Chave de release via variáveis de ambiente (secrets no GitHub Actions; localmente, exporte as mesmas variáveis).
    // Sem elas, o release é assinado com a chave de debug, o que serve só para testes.
    val releaseKeystore = System.getenv("CANDANGO_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("CANDANGO_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CANDANGO_KEY_ALIAS") ?: "candango"
            keyPassword = System.getenv("CANDANGO_KEY_PASSWORD") ?: System.getenv("CANDANGO_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // celulares reais são ARM; corta as bibliotecas nativas x86 do MapLibre (o debug mantém x86_64 para o emulador)
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "json"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    testImplementation("junit:junit:4.13.2")
}
