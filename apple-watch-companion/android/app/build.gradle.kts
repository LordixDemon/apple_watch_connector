plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "dev.applewatchandroid.companion.apple_watch_companion"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "dev.applewatchandroid.companion.apple_watch_companion"
        buildConfigField("boolean", "NATIVE_FACE_PROFILE_ISOLATED", "false")
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = "dev.applewatchandroid.companion.apple_watch_companion.OpticalDecoderInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-O2" } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    testBuildType = "release"
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/opticalAssets"))

    buildTypes {
        // Opt-in measurement app keeps the installed Companion and its drafts.
        maybeCreate("profile").apply {
            if (providers.gradleProperty("nativeFaceProfile").orNull == "true") {
                applicationIdSuffix = ".nativefaceprofile"
                buildConfigField("boolean", "NATIVE_FACE_PROFILE_ISOLATED", "true")
            }
        }
        release {
            proguardFiles("optical-proguard.pro")
            // Local tests; shared policy requires a dedicated key for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

apply(from = rootProject.file("../../apple-watch-bridge/gradle/android-signing.gradle"))

// Local firmware asset is generated from a pinned source, never from a camera
// capture. The asset and JNI loader currently support arm64 Android only.
val opticalRepository = rootProject.projectDir.resolve("../..").canonicalFile
val opticalAsset = layout.buildDirectory.file("generated/opticalAssets/optical/visual-pairing-23G71.bin")
val opticalFirmwareSource = opticalRepository.resolve("firmware/ios-26.6-23G71/extracted/VisualPairing")
val opticalScript = opticalRepository.resolve("research-tools/prepare_optical_android_asset.py")
val opticalOracle = opticalRepository.resolve("research-tools/visual_pairing_reader_oracle.py")
val opticalPython = opticalRepository.resolve("research-tools/venv-dis/bin/python")
val hasOpticalInputs = listOf(opticalFirmwareSource, opticalScript, opticalOracle, opticalPython).all { it.exists() }
val requireOpticalAsset = providers.gradleProperty("requireOpticalAsset").orNull == "true"

if (hasOpticalInputs || requireOpticalAsset) {
    val prepareOpticalAsset = tasks.register<Exec>("prepareOpticalAsset") {
        inputs.file(opticalFirmwareSource)
        inputs.files(opticalScript, opticalOracle)
        outputs.file(opticalAsset)
        commandLine(opticalPython, opticalScript, opticalFirmwareSource, opticalAsset.get().asFile)
    }
    tasks.named("preBuild") { dependsOn(prepareOpticalAsset) }
} else {
    logger.lifecycle("Skipping prepareOpticalAsset: local optical inputs not present")
}

flutter {
    source = "../.."
}
