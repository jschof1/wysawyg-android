plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val uploadSigning = listOf("SPEAKEASY_KEYSTORE_PATH", "SPEAKEASY_KEYSTORE_PASSWORD",
    "SPEAKEASY_KEY_ALIAS", "SPEAKEASY_KEY_PASSWORD").associateWith { providers.environmentVariable(it).orNull }
check(uploadSigning.values.all { it.isNullOrBlank() } || uploadSigning.values.all { !it.isNullOrBlank() }) {
    "Release signing needs all four SPEAKEASY_KEYSTORE_PATH, SPEAKEASY_KEYSTORE_PASSWORD, SPEAKEASY_KEY_ALIAS and SPEAKEASY_KEY_PASSWORD variables."
}
val hasUploadKey = uploadSigning.values.all { !it.isNullOrBlank() }
check(providers.environmentVariable("SPEAKEASY_REQUIRE_SIGNING").orNull != "true" || hasUploadKey) {
    "Signed release requested without an upload key. Configure the four signing variables; debug signing is never used for release."
}

android {
    namespace = "com.cragnet.wysawyg"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cragnet.wysawyg"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.7.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { aidl = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = false }
    androidResources { noCompress += listOf("bin", "json", "txt") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("phonon-assets"))
    sourceSets["main"].jniLibs.srcDir(providers.gradleProperty("phononJniDir")
        .orElse(layout.buildDirectory.dir("phonon-jni").map { it.asFile.absolutePath }))

    signingConfigs {
        if (hasUploadKey) create("upload") {
            storeType = "PKCS12"
            storeFile = file(requireNotNull(uploadSigning["SPEAKEASY_KEYSTORE_PATH"]))
            check(storeFile!!.isFile) { "Upload keystore does not exist at SPEAKEASY_KEYSTORE_PATH." }
            storePassword = uploadSigning["SPEAKEASY_KEYSTORE_PASSWORD"]
            keyAlias = uploadSigning["SPEAKEASY_KEY_ALIAS"]
            keyPassword = uploadSigning["SPEAKEASY_KEY_PASSWORD"]
        }
    }
    buildTypes {
        release {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
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
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("net.aspectstudio.speakeasy")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}

val modelDirectory = providers.gradleProperty("phononModelDir")
    .orElse(rootProject.layout.projectDirectory.dir(".phonon/c_weights").asFile.absolutePath)
val generatedModelAssets = layout.buildDirectory.dir("phonon-assets/phonon")
val preparePhononAssets by tasks.registering {
    outputs.upToDateWhen { false }
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
    // JniLibFolders snapshots the source folder, so the libraries must exist before it runs.
    if (name.matches(Regex("merge(Debug|Release)(JniLibFolders|NativeLibs)"))) dependsOn(preparePhononJni)
}
