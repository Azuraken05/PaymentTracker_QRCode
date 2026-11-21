plugins {
    alias(libs.plugins.android.application)
}

// Safe guard: only apply google-services plugin when google-services.json has been placed in app/
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.qr_paymentscan_cash_n_online"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.qr_paymentscan_cash_n_online"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room Local SQLite Database
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // Lifecycle LiveData & ViewModel
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")

    // WorkManager for Automatic Wi-Fi Background Sync
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.google.guava:guava:32.1.3-android")

    // Firebase Offline-First Cloud Sync (Auth, Firestore & Storage)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    // ZXing QR Code Generation & Scanning
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // FastExcel Pure Java .xlsx Generator
    implementation("org.dhatim:fastexcel:0.19.0")

    // Glide for Local Proof Image Loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// Keep IDE Live Resource Repository in sync with src/main/res after google-services resource generation
tasks.matching { it.name == "processDebugGoogleServices" }.configureEach {
    doLast {
        fileTree(layout.buildDirectory.dir("generated/res/processDebugGoogleServices")) {
            include("mipmap-*/ic_launcher*.webp")
        }.files.forEach { it.delete() }
        copy {
            from("src/main/res")
            into(layout.buildDirectory.dir("generated/res/processDebugGoogleServices"))
        }
    }
}

