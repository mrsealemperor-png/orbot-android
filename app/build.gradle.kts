import com.android.build.api.dsl.ApplicationExtension
import java.io.FileInputStream
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
}

kotlin { jvmToolchain(24) }

val orbotBaseVersionCode = 1795300100
fun getVersionName(): Provider<String> {
    // Gets the version name from the latest Git tag
    return providers.exec {
        commandLine("git", "describe", "--tags", "--always")
    }.standardOutput.asText.map { it.trim() }
}

configure<ApplicationExtension> {
    namespace = "com.seal.tyulenvpn"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.seal.tyulenvpn"
        versionCode = orbotBaseVersionCode
        versionName = getVersionName().get()
        minSdk = 24
        targetSdk = 37
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        flavorDimensions += "free"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "armeabi-v7a", "x86_64", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }

    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.canRead()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            if (!keystoreProperties.stringPropertyNames().isEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = false
            isMinifyEnabled = false
            file("proguard-android-optimize.txt").let {
                proguardFiles(getDefaultProguardFile(it.name), "proguard-rules.txt")
            }
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    productFlavors {
        create("fullperm") {
            dimension = "free"
        }
        create("nightly") {
            dimension = "free"
            applicationId = "com.seal.tyulenvpn.nightly"
            versionCode = (Date().time / 1000).toInt()
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "InvalidPackage"
        htmlReport = true
        lintConfig = file("../lint.xml")
        textReport = false
        xmlReport = false
    }
}

val updateBuiltinBridges = tasks.register<UpdateBridgeConfig>("updateBuiltinBridges") {
    description =
        "Update built in bridge JSON from torproject and guardian project DNSTT endpoints, run before making release builds"
    onlyIf { enabledForVariant.getOrElse(false) }

    assetsDir.set(layout.projectDirectory.dir("src/main/assets"))

    val dateStr: String = providers.exec {
        commandLine("git", "log", "-n", "1", "--date=unix", assetsDir.get().asFile.path)
    }.standardOutput.asText.get().trim().split("\n").filter { it.contains("Date:") }[0]
    gitLogUnixTimestamp.set(dateStr.substring("Date:".length).trim().split(" ")[0])

    gitStatusOutput.set(providers.exec {
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.map { it.trim() }.orElse(""))
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output.versionCode.get() == orbotBaseVersionCode) {
                val incrementMap =
                    mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 4, "x86_64" to 5)
                val increment =
                    incrementMap[output.filters.find { it.filterType.name == "ABI" }?.identifier]
                        ?: 0
                output.versionCode = (orbotBaseVersionCode) + increment
            }
        }
        base {
            archivesName.set("TyulenVPN-${android.defaultConfig.versionName}")
        }
        if (variant.buildType == "release") {
            updateBuiltinBridges.configure {
                enabledForVariant.set(true)
            }
            variant.sources.assets?.addGeneratedSourceDirectory(
                updateBuiltinBridges,
                UpdateBridgeConfig::assetsDir
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.localbroadcast)
    implementation(libs.androidx.window)
    implementation(libs.retrofit.converter)
    implementation(libs.rootbeer.lib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.kotlin)
    implementation(libs.upnp)
    implementation(libs.quickie)
    implementation(libs.material3)

    implementation(libs.iptproxy)
    implementation(files("../libs/geoip.jar"))
    api(libs.guardian.jtorctl)
    api(libs.tor.android)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.screengrab)
    androidTestImplementation(libs.androidx.espresso.contrib)
    androidTestUtil(libs.androidx.orchestrator)
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(copyLicenseToAssets)
    }
    tasks.named { it == "mergeNightlyDebugAssets" || it == "mergeFullpermDebugAssets" }
        .configureEach {
            mustRunAfter(updateBuiltinBridges)
        }
}

val copyLicenseToAssets = tasks.register<Copy>("copyLicenseToAssets") {
    description =
        "Copies LICENSE file from repo root to assets folder so it can be displayed in the About dialog"
    from(rootProject.file("LICENSE"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
