import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localPropsFile = rootProject.file("keystore.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) load(FileInputStream(localPropsFile))
}

// Build settings come from -P flags or keystore.properties, never defaults
// that point at someone else's server or releases.
fun setting(name: String): String = project.findProperty(name) as String? ?: localProps.getProperty(name, "")
val releaseApiUrl = setting("scriniumApiUrl")

android {
    namespace = "dev.mohak.scrinium"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mohak.scrinium"
        minSdk = 26
        targetSdk = 36
        // CI passes these from the release tag; local builds use the fallback.
        versionCode = (project.findProperty("scriniumVersionCode") as String?)?.toInt() ?: 2
        versionName = project.findProperty("scriniumVersionName") as String? ?: "0.2.0-beta"

        buildConfigField(
            "String",
            "SCRINIUM_API_URL",
            "\"$releaseApiUrl\""
        )
        buildConfigField("String", "SCRINIUM_GOOGLE_CLIENT_ID", "\"${setting("scriniumGoogleClientId")}\"")
        // owner/name of the GitHub repo the in-app updater checks; CI passes
        // its own repo, local builds leave it empty and skip update checks.
        buildConfigField("String", "UPDATE_REPO", "\"${setting("scriniumUpdateRepo")}\"")
    }

    signingConfigs {
        if (localPropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "SCRINIUM_API_URL",
                "\"${project.findProperty("scriniumApiUrl") ?: "http://10.0.2.2:5173"}\""
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (localPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releaseApiUrl.isNotEmpty()) { "Release builds need scriniumApiUrl in keystore.properties or as -PscriniumApiUrl" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ratex.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
