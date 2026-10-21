import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

fun pactLocal(): Map<*, *> {
    val file = rootProject.file("pact.local.json")
    if (!file.isFile) return emptyMap<String, Any>()
    val parsed = JsonSlurper().parse(file)
    return parsed as? Map<*, *> ?: emptyMap<String, Any>()
}

fun pactLocalText(local: Map<*, *>, key: String): String =
    (local[key] as? String)?.trim().orEmpty()

fun asBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val pactLocalValues = pactLocal()

android {
    namespace = "app.pact"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pact"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_SERVER", asBuildConfigString(pactLocalText(pactLocalValues, "server")))
        buildConfigField("String", "SHIP_UPLOAD_URL", asBuildConfigString(pactLocalText(pactLocalValues, "uploadUrl")))
        buildConfigField("String", "SHIP_DEVICE_ID", asBuildConfigString(pactLocalText(pactLocalValues, "phoneDeviceId")))
        buildConfigField("String", "SHIP_HOSTNAME", asBuildConfigString(pactLocalText(pactLocalValues, "phoneHostname")))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
