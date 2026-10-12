plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.raival.compose.file.explorer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mikhanandrij_rgb.facet"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Fresh app process per test: a hang in one test can't poison the others.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    // libmpv ships native code for four ABIs (~25 MB each): build one APK per ABI instead of
    // a ~100 MB universal APK. Phones need arm64-v8a (or armeabi-v7a on old devices).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // Extract-free loading of native libraries straight from the APK.
            useLegacyPackaging = false
            // Prebuilt libmpv libraries are already release-stripped; skipping AGP's strip
            // step means the build doesn't need (or download) an NDK.
            keepDebugSymbols += "**/*.so"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        // Shared, public debug key (not a secret): every CI build is signed the same way,
        // so newer debug/nightly builds install over older ones.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    baselineProfile {
        dexLayoutOptimization = true
    }
}

dependencies {
    "baselineProfile"(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Local/File-based dependencies
    implementation(files("libs/APKEditor.jar"))

    // AndroidX - Core & Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.ui.tooling.preview.android)

    // Other Jetpack & Android Libraries
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.libmpv)
    implementation(libs.androidx.palette.ktx)

    // Sora Code Editor
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.language.java)
    implementation(libs.sora.editor.language.textmate)

    // Image Loading - Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.zoomable.image.coil3)
    implementation(libs.okio)

    // Third-Party UI/Compose Utilities
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.cascade.compose)
    implementation(libs.compose.swipebox)
    implementation(libs.grid)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.reorderable)
    implementation(libs.zoomable)

    // Third-Party General Utilities
    implementation(libs.apksig)
    implementation(libs.commons.net)
    implementation(libs.gson)
    implementation(libs.storage)
    implementation(libs.zip4j)

    // Unit tests
    testImplementation(libs.junit4)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
}