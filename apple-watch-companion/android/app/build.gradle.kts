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
val prepareOpticalAsset = tasks.register<Exec>("prepareOpticalAsset") {
    inputs.file(opticalRepository.resolve("firmware/ios-26.6-23G71/extracted/VisualPairing"))
    inputs.files(opticalRepository.resolve("research-tools/prepare_optical_android_asset.py"),
        opticalRepository.resolve("research-tools/visual_pairing_reader_oracle.py"))
    outputs.file(opticalAsset)
    commandLine(opticalRepository.resolve("research-tools/venv-dis/bin/python"),
        opticalRepository.resolve("research-tools/prepare_optical_android_asset.py"),
        opticalRepository.resolve("firmware/ios-26.6-23G71/extracted/VisualPairing"), opticalAsset.get().asFile)
}
tasks.named("preBuild") { dependsOn(prepareOpticalAsset) }

flutter {
    source = "../.."
}
