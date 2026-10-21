plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cragnet.wysawyg"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cragnet.wysawyg"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { aidl = true }
    // AGP 8.5.0 cannot zip-align uncompressed JNI for 16 KiB devices. Compress/extract
    // the small native libraries; their ELF segments are independently 16 KiB aligned.
    packaging { jniLibs.useLegacyPackaging = true }
    androidResources { noCompress += listOf("bin", "json", "txt") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("phonon-assets"))
    sourceSets["main"].jniLibs.srcDir(providers.gradleProperty("phononJniDir")
        .orElse(layout.buildDirectory.dir("phonon-jni").map { it.asFile.absolutePath }))

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Unit tests and Kotlin compilation need neither the 255 MB model nor an NDK.
// APK asset/native merging fails closed if pinned inputs are absent or changed.
val modelDirectory = providers.gradleProperty("phononModelDir")
    .orElse(rootProject.layout.projectDirectory.dir(".phonon/c_weights").asFile.absolutePath)
val generatedModelAssets = layout.buildDirectory.dir("phonon-assets/phonon")
val preparePhononAssets by tasks.registering {
    outputs.upToDateWhen { false } // verify cached source AND packaged assets on every build
    doLast {
        project.exec {
            commandLine("python3", rootProject.file("scripts/prepare-phonon-model.py"),
                "--model-dir", modelDirectory.get())
        }
        project.copy {
            from(modelDirectory) { include("weights.bin", "weights.json", "tokens.txt") }
            into(generatedModelAssets)
        }
        project.exec {
            commandLine("python3", rootProject.file("scripts/prepare-phonon-model.py"),
                "--model-dir", generatedModelAssets.get().asFile)
        }
    }
}
val preparePhononJni by tasks.registering {
    outputs.upToDateWhen { false }
    doLast {
        val external = providers.gradleProperty("phononJniDir").orNull
        val directory = external ?: layout.buildDirectory.dir("phonon-jni").get().asFile.absolutePath
        if (external == null) {
            project.exec { commandLine("bash", rootProject.file("scripts/build-phonon-native.sh"), directory) }
        }
        project.exec { commandLine("python3", rootProject.file("scripts/native-manifest.py"), "verify", directory) }
    }
}
tasks.configureEach {
    if (name.matches(Regex("merge(Debug|Release)Assets"))) dependsOn(preparePhononAssets)
    if (name.matches(Regex("merge(Debug|Release)NativeLibs"))) dependsOn(preparePhononJni)
}
