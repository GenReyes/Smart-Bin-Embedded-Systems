import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // The code lives in the short package "smartbin" so the source folders stay shallow.
    namespace = "smartbin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "edu.tup.smartbin"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        // The team's Firebase account, read from local.properties (git-ignored) so the
        // password never reaches the public repository. Missing values become empty.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
        }
        fun prop(key: String) = "\"" + (local.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "FB_URL", prop("sb.fb.url"))
        buildConfigField("String", "FB_KEY", prop("sb.fb.key"))
        buildConfigField("String", "FB_EMAIL", prop("sb.fb.email"))
        buildConfigField("String", "FB_PASSWORD", prop("sb.fb.password"))
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
