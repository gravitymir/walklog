import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9+ содержит встроенную поддержку Kotlin — отдельный kotlin-плагин не нужен.
    alias(libs.plugins.android.application)
}

android {
    namespace = "ie.onfoot.walklog"
    compileSdk = 37

    defaultConfig {
        applicationId = "ie.onfoot.walklog"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
