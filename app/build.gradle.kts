import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Secrets (e.g. GROQ_API_KEY used for CI smoke builds) are optional. When a `keystore.properties`
 * file exists the release build type is wired up for signing; debug builds never require it.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.typorb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.typorb"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "GROQ_BASE_URL", "\"https://api.groq.com\"")
            buildConfigField("String", "GROQ_TRANSCRIBE_MODEL", "\"whisper-large-v3\"")
            buildConfigField("String", "GROQ_CHAT_MODEL", "\"llama-3.1-8b-instant\"")
            buildConfigField("String", "WHISPER_ASSET_PATH", "\"whisper/whisper-tiny.onnx\"")
            buildConfigField("String", "WHISPER_DECODER_ASSET_PATH", "\"whisper/whisper-tiny-decoder.onnx\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "GROQ_BASE_URL", "\"https://api.groq.com\"")
            buildConfigField("String", "GROQ_TRANSCRIBE_MODEL", "\"whisper-large-v3\"")
            buildConfigField("String", "GROQ_CHAT_MODEL", "\"llama-3.1-8b-instant\"")
            buildConfigField("String", "WHISPER_ASSET_PATH", "\"whisper/whisper-tiny.onnx\"")
            buildConfigField("String", "WHISPER_DECODER_ASSET_PATH", "\"whisper/whisper-tiny-decoder.onnx\"")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX core + Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (Cloud engine)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Secure credential storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Offline inference engine
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    testImplementation("junit:junit:4.13.2")
    // Android provides org.json at runtime; unit tests need it on the JVM classpath too.
    testImplementation("org.json:json:20231013")
}