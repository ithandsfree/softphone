import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing. The keystore lives outside git (see docs/APK_SIGNING.md).
 * Without it `assembleRelease` still works but falls back to the debug key,
 * which forces an uninstall on every update.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (signingProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("storeFile", "IHF_KEYSTORE_FILE")
val releaseStorePass = signingValue("storePassword", "IHF_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "IHF_KEY_ALIAS")
val releaseKeyPass = signingValue("keyPassword", "IHF_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePass, releaseKeyAlias, releaseKeyPass)
    .all { it != null } && file(releaseStorePath ?: ".").exists()

android {
    namespace = "net.ithandsfree.softphone"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "0.3.12"
        // HTTPS enrol app-link host. Override per deployment; this tree ships a placeholder.
        manifestPlaceholders["enrolHost"] = "pbx.example.com"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("ihf") {
            dimension = "distribution"
            applicationId = "net.ithandsfree.softphone"
            resValue("string", "app_name", "IHF Phone")
            // Placeholders only. Do not commit a live PBX hostname into this repo.
            buildConfigField("String", "DEFAULT_API_BASE", "\"https://pbx.example.com/ihf-softphone/index.php\"")
            buildConfigField("String", "DEFAULT_SIP_DOMAIN", "\"pbx.example.com\"")
            buildConfigField("boolean", "SERVER_EDITABLE", "false")
            buildConfigField("String", "DEFAULT_SKIN_ID", "\"ihf_night\"")
            buildConfigField("String", "PRODUCT_NAME", "\"IHF Phone\"")
            buildConfigField("String", "BRAND_SUB", "\"IT HANDS FREE\"")
            buildConfigField("String", "FOOTER_TAG", "\"FreePBX softphone\"")
            buildConfigField("String", "USER_AGENT_NAME", "\"IHF-Softphone\"")
            // IHF listing only. The privacy URL is a Play requirement, not GPL.
            buildConfigField("boolean", "SHOW_IHF_PRIVACY", "true")
        }
        create("community") {
            dimension = "distribution"
            applicationId = "net.ithandsfree.softphone.community"
            resValue("string", "app_name", "Community Softphone")
            buildConfigField("String", "DEFAULT_API_BASE", "\"\"")
            buildConfigField("String", "DEFAULT_SIP_DOMAIN", "\"\"")
            buildConfigField("boolean", "SERVER_EDITABLE", "true")
            buildConfigField("String", "DEFAULT_SKIN_ID", "\"carbon_signal\"")
            buildConfigField("String", "PRODUCT_NAME", "\"Community Softphone\"")
            buildConfigField("String", "BRAND_SUB", "\"FREEPBX\"")
            buildConfigField("String", "FOOTER_TAG", "\"FreePBX 17+\"")
            buildConfigField("String", "USER_AGENT_NAME", "\"Community-Softphone\"")
            buildConfigField("boolean", "SHOW_IHF_PRIVACY", "false")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
                // minSdk 26 never needs v1; v3 keeps key rotation available.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // Encrypted prefs for UM/SIP secrets on device
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // PJSIP PJSUA2 (PjDroid packaging). See sip/PjsipNotes.md for licensing.
    implementation("com.pjdroid:pjdroid:2.2.4")

    testImplementation("junit:junit:4.13.2")
}
