
import com.unciv.build.AndroidImagePacker
import com.unciv.build.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    compileSdk = 36
    sourceSets {
        getByName("main").apply {
            manifest.srcFile("AndroidManifest.xml")
            java.directories += "src"
            kotlin.directories += "src"
            aidl.directories += "src"
            res.directories += "res"
            assets.directories += "assets"
            jniLibs.directories += "libs"
        }
    }
    packaging {
        resources.excludes += "META-INF/robovm/ios/robovm.xml"
        // part of kotlinx-coroutines-android, should not go into the apk
        resources.excludes += "DebugProbesKt.bin"
    }
    defaultConfig {
        namespace = BuildConfig.namespace
        applicationId = BuildConfig.applicationId
        // Bumped from upstream Unciv's minSdk 21: com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8
        // requires minSdk 24. Any device that can run a Solana wallet app (Phantom/Solflare etc, both
        // also minSdk 24+) is already well above API 21-23, so this doesn't meaningfully narrow this
        // fork's real-world install base.
        minSdk = 24
        targetSdk = 36
        versionCode = BuildConfig.appCodeNumber
        versionName = BuildConfig.appVersion

        base.archivesName.set(BuildConfig.appName)
    }

    // Had to add this crap for Travis to build, it wanted to sign the app
    // but couldn't create the debug keystore for some reason

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("android/debug.keystore")
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // If you make this true you get a version of the game that just flat-out doesn't run
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
        }
    }

    lint {
        disable += "MissingTranslation"   // see res/values/strings.xml
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        // Don't add local save files and fonts to release, obviously
        ignoreAssetsPattern = "!SaveFiles:!fonts:!maps:!music:!mods"
    }
    buildFeatures {
        aidl = true
    }
}

// necessary for Android WorkManager lib, used in MultiplayerTurnCheckWorker
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

tasks.register("texturePacker") {
    doFirst {
        logger.info("Calling TexturePacker")
        AndroidImagePacker.packImages(projectDir.path)
    }
}

// called every time gradle gets executed, takes the native dependencies of
// the natives configuration, and extracts them to the proper libs/ folders
// so they get packed with the APK.
tasks.register("copyAndroidNatives") {
    val natives: Configuration by configurations

    doFirst {
        val rx = Regex(""".*natives-([^.]+)\.jar$""")
        natives.forEach { jar ->
            if (rx.matches(jar.name)) {
                val outputDir = file(rx.replace(jar.name) { "libs/" + it.groups[1]!!.value })
                outputDir.mkdirs()
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
    dependsOn("texturePacker")
}

tasks.whenTaskAdded {
    // See https://github.com/yairm210/Unciv/issues/4842
    if ("package" in name || "assemble" in name || "bundleRelease" in name) {
        dependsOn("copyAndroidNatives")
    }
}

private fun getSdkPath(): String? {
    val localProperties = project.file("../local.properties")
    return if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { properties.load(it) }

        properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    } else {
        System.getenv("ANDROID_HOME")
    }
}

tasks.register<Exec>("run") {
    standardOutput = System.out
    errorOutput = System.err
    isIgnoreExitValue = false

    val path = getSdkPath()
    val adb = "$path/platform-tools/adb"

    commandLine(adb, "shell", "am", "start", "-n", "${BuildConfig.applicationId}/com.unciv.app.AndroidLauncher")
}

dependencies {
    implementation(libs.android.ktx.core)
    implementation(libs.android.ktx.runtime)
    // Needed to convert e.g. Android 26 API calls to Android 21
    // If you remove this run `./gradlew :android:lintDebug` to ensure everything's okay.
    // If you want to upgrade this, check it's working by building an apk,
    //   or by running `./gradlew :android:assembleRelease` which does that
    coreLibraryDesugaring(libs.android.desugar)

    // Solana Mobile Wallet Adapter - wallet connect/sign for AndroidWalletService.
    // web3-solana pulls in the ktor client bits it needs for RPC (KtorNetworkDriver); core
    // already exposes the ktor-client bundle as `api` so this should resolve transitively too.
    // NOT YET VERIFIED (blocked on Maven Central 403s from this environment, confirm on first
    // real `./gradlew` sync):
    //  - web3-core (upstream of web3-solana) pins ktor = "3.3.0" in its own version catalog,
    //    vs. this repo's ktor = "3.2.3" (gradle/libs.versions.toml). Likely fine - Ktor client
    //    APIs used here (ktor-client-core/cio) are stable across 3.x minors - but Gradle's
    //    resolved version could end up either one; watch for a resolution/runtime mismatch.
    //  - solanaWeb3 = "0.3.0": couldn't confirm this exact version exists / is the intended
    //    "latest stable" via Maven Central metadata (403s). A "0.3.2-beta6" also exists upstream;
    //    if 0.3.0 fails to resolve, that's the next thing to try.
    implementation(libs.solana.mwa.clientlib)
    implementation(libs.solana.web3)
    // Explicit dep for androidx.activity.ComponentActivity used by AndroidWalletService /
    // ActivityResultSender - likely already pulled in transitively by the MWA clientlib, but
    // declared explicitly so compilation doesn't depend on that assumption.
    implementation(libs.androidx.activity)
}
