plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.htb.carplan.shared"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(platform(libs.firebase.bom))
    api(libs.firebase.firestore)
    api(libs.firebase.storage)
    api(libs.firebase.auth)
    api(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
