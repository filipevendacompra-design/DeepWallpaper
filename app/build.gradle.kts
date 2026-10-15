plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.filipe.profundo"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.filipe.profundo"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // Chave fixa: cada build tem a mesma assinatura, por isso as atualizações instalam por cima
    signingConfigs {
        create("fixa") {
            storeFile = file("profundo.keystore")
            storePassword = "profundo123"
            keyAlias = "profundo"
            keyPassword = "profundo123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // O modelo de profundidade é mapeado em memória diretamente do APK
    androidResources {
        noCompress += listOf("tflite")
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
}
