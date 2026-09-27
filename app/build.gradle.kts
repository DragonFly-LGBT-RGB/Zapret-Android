import com.android.build.gradle.tasks.MergeSourceSetFolders

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// byedpi sources are fetched by CI (or scripts/fetch-native-sources.sh) into src/main/cpp/byedpi
val byedpiDir = file("src/main/cpp/byedpi")
// hev-socks5-tunnel sources are fetched into src/main/jni/hev-socks5-tunnel
val hevDir = file("src/main/jni/hev-socks5-tunnel")

android {
    namespace = "ru.dragonfly.zapret"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "ru.dragonfly.zapret"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(abis)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
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
        compose = true
    }

    packaging {
        jniLibs {
            // required: we execute bin/nfqws which is shipped as lib*.so
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/*
 * Zapret data (strategies, lists, fake payloads) lives in the repository root and is
 * packaged into the APK assets, so the app stays in sync with the desktop version.
 */
val zapretAssetsDir = layout.buildDirectory.dir("generated/zapret-assets")

val copyZapretAssets = tasks.register<Copy>("copyZapretAssets") {
    into(zapretAssetsDir)
    from(rootDir) {
        include("*.bat")
        exclude("service.bat", "gradlew.bat")
        into("strategies")
    }
    from(rootDir.resolve("lists")) {
        include("*.txt")
        into("lists")
    }
    from(rootDir.resolve("bin")) {
        include("*.bin")
        into("bin")
    }
    from(rootDir.resolve("utils")) {
        include("targets.txt")
    }
}

android.sourceSets["main"].assets.srcDir(zapretAssetsDir)

tasks.withType<MergeSourceSetFolders>().configureEach {
    dependsOn(copyZapretAssets)
}

/*
 * hev-socks5-tunnel (MIT) is built with ndk-build, exactly like upstream recommends.
 * The task is skipped when the sources have not been fetched yet.
 */
val buildHevTunnel = tasks.register<Exec>("buildHevTunnel") {
    onlyIf { hevDir.resolve("Android.mk").exists() }
    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    args(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk",
        "-j${Runtime.getRuntime().availableProcessors()}"
    )
}

tasks.named("preBuild") {
    dependsOn(buildHevTunnel)
}

tasks.register("printNativeStatus") {
    doLast {
        println("byedpi sources present: ${byedpiDir.resolve("main.c").exists()}")
        println("hev-socks5-tunnel sources present: ${hevDir.resolve("Android.mk").exists()}")
    }
}
