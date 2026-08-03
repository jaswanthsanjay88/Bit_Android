import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.google.dagger.hilt)
}

val localPropertiesFile = rootProject.file("local.properties")

android {
    namespace = "com.bit"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.bit"
        minSdk = 29
        targetSdk = 36
        versionCode = 67
        versionName = "1.9.8"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("String", "ALIAS", getProperty("ALIAS"))
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:qnnlibs.tar.xz"
    }

    signingConfigs {
        create("release") {
            val localProps = Properties().apply {
                val propFile = rootProject.file("local.properties")
                if (propFile.exists()) {
                    FileInputStream(propFile).use { load(it) }
                }
            }

            val ksPath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
                ?: localProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
                ?: rootProject.file("release-key.jks").absolutePath

            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                val ksPass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: localProps.getProperty("storePassword")?.takeIf { it.isNotBlank() }
                val alias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                    ?: localProps.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
                val keyPass = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: localProps.getProperty("keyPassword")?.takeIf { it.isNotBlank() }

                if (!ksPass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                    storeFile = ksFile
                    storePassword = ksPass.trim()
                    keyAlias = alias.trim()
                    keyPassword = keyPass.trim()
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val relConfig = signingConfigs.findByName("release")
            if (relConfig?.storeFile != null && !relConfig.storePassword.isNullOrBlank()) {
                signingConfig = relConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libai_sherpa.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/version-control-info.textproto",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "assets/qnnlibs/qnnlibs.tar.xz"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {

    // Local AI Libraries (first priority for JNI pickFirsts)
    implementation("com.github.jaswanthsanjay88.bit-dependencies:ai_sherpa_v3:1.1.0@aar")
    implementation("com.github.jaswanthsanjay88.bit-dependencies:ai_sd:1.1.0@aar")
    implementation(libs.onnxruntime.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Background Tasks & Networking
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.jsoup)

    // Document Parsing
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.slf4j.android)

    // Database & Storage
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Serialization & API
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Local Projects & AI Libraries
    // Resolved from JitPack for F-Droid compliance
    implementation(project(":llama-kt"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(project(":memory-vault"))
    implementation(project(":neuron-packet"))
    implementation(project(":system_encryptor"))
    implementation(project(":file_ops"))
    implementation(project(":ums"))
    //implementation(project(":character-engine"))

    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.liquid)
    implementation(libs.backdrop)
    implementation(libs.haze)

    // Material Design
    implementation(libs.androidx.material)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX for Vision AI
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}

fun getProperty(value: String): String {
    val raw = if (localPropertiesFile.exists()) {
        val localProps = Properties().apply {
            load(FileInputStream(localPropertiesFile))
        }
        localProps.getProperty(value) ?: "dev_alias"
    } else {
        System.getenv(value) ?: "dev_alias"
    }
    val clean = raw.trim().removeSurrounding("\"").removeSurrounding("'")
    return "\"$clean\""
}
