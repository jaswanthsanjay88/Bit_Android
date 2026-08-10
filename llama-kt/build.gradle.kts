plugins {
    id("com.android.library")
}

android {
    namespace = "com.dark.gguf_lib"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29

        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared",
                          "-DBUILD_SHARED_LIBS=ON",
                          "-DLLAMA_BUILD_TESTS=OFF",
                          "-DLLAMA_BUILD_EXAMPLES=OFF",
                          "-DLLAMA_BUILD_TOOLS=OFF",
                          "-DLLAMA_BUILD_SERVER=OFF",
                          "-DGGML_NATIVE=OFF",
                          "-DGGML_BACKEND_DL=OFF",
                          "-DGGML_CPU_ALL_VARIANTS=OFF",
                          "-DGGML_LLAMAFILE=OFF",
                          "-Wno-deprecated",
                          "-Wno-dev")
                abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "consumer-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}

val pdfiumNatives by configurations.creating

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // PDFium native binaries from JitPack dependency
    pdfiumNatives("com.github.jaswanthsanjay88.bit-dependencies:pdfium_libs:1.1.0@zip")
}

val extractPdfium by tasks.registering(Copy::class) {
    from(pdfiumNatives.map { zipTree(it) })
    into(file("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn(extractPdfium)
}

