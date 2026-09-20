
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rennvol.miniarcade"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rennvol.miniarcade"
        minSdk = 24
        targetSdk = 34
        val runNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val commitCount = try {
            val proc = ProcessBuilder("git","rev-list","--count","HEAD").redirectErrorStream(true).start()
            proc.waitFor()
            String(proc.inputStream.readAllBytes()).trim().toInt()
        } catch (_: Exception) { 0 }
        versionCode = runNum ?: if (commitCount>0) commitCount else 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = "miniarcade"
            keyAlias = "miniarcade"
            keyPassword = "miniarcade"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
