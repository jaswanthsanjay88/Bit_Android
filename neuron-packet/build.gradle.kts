plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.neuronpacket"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 27
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -fexceptions")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val opensslNatives by configurations.creating

dependencies {
    implementation(libs.lz4.java)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    // OpenSSL native binaries from JitPack dependency
    opensslNatives("com.github.jaswanthsanjay88.bit-dependencies:openssl_libs:1.1.0@zip")
}

val extractOpenSsl by tasks.registering(Copy::class) {
    from(opensslNatives.map { zipTree(it) })
    into(file("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn(extractOpenSsl)
}

