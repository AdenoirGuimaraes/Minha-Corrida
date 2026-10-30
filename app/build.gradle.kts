plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Assinatura fixa (necessária para atualizar por cima). Vem de variáveis de ambiente
// preenchidas pelos segredos do GitHub; sem elas, usa a chave de debug.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val hasReleaseSigning = !signingStoreFile.isNullOrBlank() && file(signingStoreFile!!).exists()

android {
    namespace = "br.com.minhacorrida"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.minhacorrida"
        minSdk = 26
        targetSdk = 34
        // Número da compilação no GitHub Actions: cresce a cada versão publicada.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.2.${versionCode}"
        buildConfigField(
            "String",
            "RELEASE_API_URL",
            "\"https://api.github.com/repos/AdenoirGuimaraes/Minha-Corrida/releases/tags/ultima-versao\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    // OCR no próprio aparelho (modelo latino embutido, funciona offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
