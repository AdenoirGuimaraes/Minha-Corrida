plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Assinatura fixa (necessária para o Android aceitar a atualização por cima).
// A chave NÃO fica no repositório: o GitHub Actions a recria a partir do segredo
// SIGNING_KEYSTORE_BASE64, e a senha vem de SIGNING_STORE_PASSWORD. Sem eles, usa a chave de debug.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val signingPassword: String? = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = signingStoreFile != null && signingPassword != null && file(signingStoreFile).exists()

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
                storePassword = signingPassword
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "minha-corrida"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: signingPassword
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
