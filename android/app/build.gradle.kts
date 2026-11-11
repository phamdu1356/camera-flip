import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.phamdu.flipcamera"
    compileSdk = 34
    sourceSets.getByName("main").assets.srcDir(file("../../viewer"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/viewerAppAssets"))
    defaultConfig {
        applicationId = "com.phamdu.flipcamera"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["recordingDurationMs"] =
            providers.gradleProperty("recordingDurationMs").getOrElse("3000")
        testInstrumentationRunnerArguments["recordingQuality"] =
            providers.gradleProperty("recordingQuality").getOrElse("SD")
        testInstrumentationRunnerArguments["screenOffDuringRecording"] =
            providers.gradleProperty("screenOffDuringRecording").getOrElse("false")
        providers.gradleProperty("instrumentationTestClass").orNull?.let { testClass ->
            testInstrumentationRunnerArguments["class"] = testClass
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
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            pickFirsts += "META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Version-aligned CameraX modules; hardware integration remains a later gate.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-effects:1.4.1")

    // Embedded HTTPS viewer server; verify Netty runtime compatibility on Android.
    implementation("io.ktor:ktor-server-netty:3.6.0") {
        // The app serves HTTP/1.1 over TCP; Netty's optional QUIC/native HTTP/3 variants
        // are desktop/server artifacts and add duplicate platform license resources.
        exclude(group = "io.netty", module = "netty-codec-http3")
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
}

// Nhúng Flip Viewer (viewer-app) vào APK máy chủ để viewer tự cập nhật qua HTTPS (không dùng ADB).
val packageViewerApp = tasks.register("packageViewerApp") {
    val viewerApk = rootProject.file("viewer-app/build/outputs/apk/debug/viewer-app-debug.apk")
    val viewerBuild = rootProject.file("viewer-app/build.gradle.kts")
    val outDir = layout.buildDirectory.dir("generated/viewerAppAssets")
    dependsOn(":viewer-app:assembleDebug")
    inputs.files(viewerApk, viewerBuild)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        viewerApk.copyTo(File(dir, "viewer-app.apk"), overwrite = true)
        val text = viewerBuild.readText()
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(text)!!.groupValues[1].toInt()
        val name = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(text)!!.groupValues[1]
        val sha = MessageDigest.getInstance("SHA-256").digest(viewerApk.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(dir, "viewer-app.json").writeText(
            """{"packageName":"com.phamdu.flipviewer.debug","versionCode":$code,"versionName":"$name","sha256":"$sha","size":${viewerApk.length()}}"""
        )
    }
}
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach { dependsOn(packageViewerApp) }
