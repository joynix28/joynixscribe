import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropsFile = sequenceOf(
    rootProject.file("keystore.properties"),
    rootProject.file("signing/keystore.properties"),
).firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    if (keystorePropsFile != null) keystorePropsFile.inputStream().use { load(it) }
}
val keystoreStoreFile = System.getenv("JOYNIXSCRIBE_STORE_FILE")
    ?: keystoreProps.getProperty("storeFile")
val keystoreStorePassword = System.getenv("JOYNIXSCRIBE_STORE_PASSWORD")
    ?: keystoreProps.getProperty("storePassword")
val keystoreKeyAlias = System.getenv("JOYNIXSCRIBE_KEY_ALIAS")
    ?: keystoreProps.getProperty("keyAlias")
val keystoreKeyPassword = System.getenv("JOYNIXSCRIBE_KEY_PASSWORD")
    ?: keystoreProps.getProperty("keyPassword")
val hasSigning = !keystoreStoreFile.isNullOrBlank()

fun resolveKeystore(path: String): File {
    val file = File(path)
    if (file.isAbsolute) return file
    // Chemins relatifs interprétés depuis la racine du dépôt, comme avant.
    return rootProject.file(path)
}

android {
    namespace = "com.botillon.flowscribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.botillon.flowscribe"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (hasSigning) {
                storeFile = resolveKeystore(keystoreStoreFile!!)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = if (hasSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
