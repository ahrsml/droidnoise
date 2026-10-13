plugins {
    id("com.android.application")
}

// Minutos desde epoch: crece en cada build, así cada APK se instala encima del anterior.
val buildVersionCode: Int =
    (findProperty("versionCode") as String?)?.toInt()
        ?: (System.currentTimeMillis() / 60_000).toInt()

android {
    namespace = "cl.droidnoise.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.droidnoise.player"
        minSdk = 29
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = "0.1.$buildVersionCode"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Keystore de debug versionado: misma firma en cualquier máquina.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.github.libpd.android:pd-core:1.2.1-rc6")
}
