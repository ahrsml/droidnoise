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
        // Keystore de debug propio (keystore/debug.keystore, fuera de git): misma firma siempre,
        // para instalar encima en el celu. Si no está (clon nuevo), se usa el debug estándar de Android.
        val own = rootProject.file("keystore/debug.keystore")
        if (own.isFile) {
            getByName("debug") {
                storeFile = own
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Logo de SÜDBÖS: el original (de Isidora Mansilla, derechos reservados) vive en private/,
    // fuera de git. Sin él, el build usa la copia marcada del repo (app/branding/).
    sourceSets {
        getByName("main") {
            val original = rootProject.file("private/res")
            res.srcDir(if (original.isDirectory) original else file("branding/res"))
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
