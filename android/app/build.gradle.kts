plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Auto-fetch the sherpa-onnx AAR (~55 MB) into app/libs/ on first build.
// Idempotent — skips if the file is already present with the right SHA-256.
val fetchSherpaOnnx by tasks.registering(Exec::class) {
    val script = file("${rootDir}/scripts/fetch-libs.sh")
    val aar = file("${projectDir}/libs/sherpa-onnx-1.13.6.aar")
    inputs.file(script)
    outputs.file(aar)
    commandLine("bash", script.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(fetchSherpaOnnx) }

android {
    namespace = "com.peter.mutter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.peter.mutter"
        minSdk = 34
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(files("libs/sherpa-onnx-1.13.6.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20240303")
}
