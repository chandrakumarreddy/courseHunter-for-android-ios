plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val lynxVersion = "4.1.0"
val lynxDist = rootProject.file("../dist")
val lynxAssetsDir = layout.buildDirectory.dir("generated/lynx/assets").get().asFile

// Release signing key; kept out of version control. Back up release.jks: updates must be signed with the same key.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

val syncLynxBundle by tasks.registering(Sync::class) {
    doFirst {
        check(lynxDist.resolve("main.lynx.bundle").exists()) {
            "$lynxDist/main.lynx.bundle not found. Run 'bun run build' first."
        }
    }
    from(lynxDist) { include("*.lynx.bundle") }
    into(lynxAssetsDir)
}

android {
    namespace = "net.coursehunter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.coursehunter.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // Every phone since ~2017; each extra ABI adds 12-19 MB of Lynx native libs.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        // Need libv8_libfull, which Lynx doesn't ship; they can never load.
        jniLibs.excludes += setOf("**/libnapi_v8.so", "**/liblynx_v8_bridge.so")
        resources.excludes += setOf("okhttp3/internal/publicsuffix/*", "DebugProbesKt.bin")
    }

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    sourceSets["release"].assets.srcDir(lynxAssetsDir)

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            ndk.debugSymbolLevel = "NONE"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(syncLynxBundle) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // primjsWasm only adds WebAssembly, which the app doesn't use; Lynx logs and carries on without it.
    implementation("org.lynxsdk.lynx:lynx:$lynxVersion") { exclude(group = "org.lynxsdk.lynx", module = "primjsWasm") }
    implementation("org.lynxsdk.lynx:lynx-jssdk:$lynxVersion")
    implementation("org.lynxsdk.lynx:lynx-trace:$lynxVersion")
    implementation("org.lynxsdk.lynx:primjs:4.1.1")

    implementation("org.lynxsdk.lynx:lynx-service-log:$lynxVersion")
    implementation("org.lynxsdk.lynx:lynx-service-http:$lynxVersion")
    implementation("org.lynxsdk.lynx:xelement-webview:$lynxVersion")

    implementation("com.squareup.okhttp3:okhttp:4.9.0")
}
