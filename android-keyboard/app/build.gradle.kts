plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.barkboard.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.barkboard.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Voice typing needs the sherpa-onnx prebuilts (headers + .so), which are fetched by
    // tools/download-sherpa-onnx.sh rather than committed. Keyed off the presence of the library
    // so a checkout without them still builds and installs: the mic key then reports the model as
    // unavailable instead of the build failing. See docs/native-engine.md.
    if (file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so").isFile) {
        ndkVersion = "29.0.14206865"

        defaultConfig {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }

        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
