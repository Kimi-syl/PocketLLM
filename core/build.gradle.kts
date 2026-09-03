import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // Kotlin/Native can only run on x86_64 Linux/macOS/Windows hosts — this
    // device is aarch64, so iOS targets (and the XCFramework) are built on
    // CI (see .github/workflows/ios.yml, which passes -PenableIos=true).
    if (project.findProperty("enableIos") == "true") {
        val xcf = XCFramework("PocketLLMKit")
        iosArm64 {
            binaries.framework {
                baseName = "PocketLLMKit"
                xcf.add(this)
            }
        }
        iosX64 {
            binaries.framework {
                baseName = "PocketLLMKit"
                xcf.add(this)
            }
        }
        iosSimulatorArm64 {
            binaries.framework {
                baseName = "PocketLLMKit"
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.okhttp)
            implementation(libs.jsoup)
        }
    }
}
