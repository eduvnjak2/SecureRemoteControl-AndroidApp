plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "ba.unsa.etf.si.secureremotecontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "ba.unsa.etf.si.secureremotecontrol"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        //freeCompilerArgs += listOf("-Xlint:deprecation")
        freeCompilerArgs = freeCompilerArgs - "-Xlint:deprecation"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    kapt {
        correctErrorTypes = true
    }
}

dependencies {
    implementation(libs.androidx.recyclerview)
    val composeBomVersion = "2024.04.00" // Use the latest stable BOM version
    implementation(platform("androidx.compose:compose-bom:$composeBomVersion"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    //implementation("androidx.activity:activity-compose:1.8.2")
    //implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    implementation(libs.androidx.lifecycle.process)
    kapt("com.google.dagger:hilt-android-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Arrow
    implementation("io.arrow-kt:arrow-core:1.2.0")
    implementation("io.arrow-kt:arrow-fx-coroutines:1.2.0")
    
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // WebRTC
    //implementation ("io.antmedia:webrtc-android-framework:2.8.0-SNAPSHOT")

    implementation ("com.mesibo.api:webrtc:1.0.5")

    // Foreground
    implementation ("androidx.core:core-ktx:1.9.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase Cloud Messaging (FCM)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Coil
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    //Datastore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    //DocumentFile
    implementation ("androidx.documentfile:documentfile:1.0.1")

    //RecycleView
    implementation("androidx.recyclerview:recyclerview:1.3.1")

    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
configurations.all {
    resolutionStrategy {
        force ("androidx.recyclerview:recyclerview:1.3.1")
    }
}

apply(plugin = "com.google.gms.google-services")
