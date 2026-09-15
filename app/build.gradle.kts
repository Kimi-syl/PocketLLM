plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pocketllm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketllm"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.4.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
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
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Legacy packaging: the native libraries are compressed in the APK and
        // extracted at install time, instead of being loaded directly from the
        // APK. That is the traditional path and the most broadly compatible, so
        // it sidesteps installers that mishandle uncompressed, page-aligned
        // libs (this started as a diagnostic while an install failure was being
        // tracked down, and is harmless to keep).
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
    // Live2D Cubism Core: the native JNI library plus its Java wrapper. Listed in
    // the SDK's RedistributableFiles.txt, so it may be shipped inside the APK.
    implementation(files("libs/Live2DCubismCore.aar"))
    // Filament: Google's real-time 3D renderer, for VRM avatars. VRM is glTF 2.0
    // plus extensions, so gltfio loads the mesh and filament-utils supplies the
    // viewer scaffolding.
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation("com.google.android.filament:filament-utils-android:1.75.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.jsoup)
    implementation(libs.pdfbox) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.strikethrough)
}
