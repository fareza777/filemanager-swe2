import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.filezen.files"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filezen.files"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // PRODUCTION TODO: replace with your real AdMob App ID from
        // https://apps.admob.com → App settings. This is Google's public TEST app ID.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Upload key when keystore.properties exists (Play release); debug key
            // as a sideload-friendly fallback so the release APK still installs.
            signingConfig = if (rootProject.file("keystore.properties").exists())
                signingConfigs.getByName("releaseUpload") else signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        create("releaseUpload") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile", "release.keystore"))
                storePassword = props.getProperty("storePassword", "")
                keyAlias = props.getProperty("keyAlias", "filezen-upload")
                keyPassword = props.getProperty("keyPassword", "")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        resources.pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1") // Safe Share metadata cleaner
    implementation("dev.rikka.shizuku:api:13.1.5")             // Shizuku power access
    implementation("dev.rikka.shizuku:provider:13.1.5")       // Shizuku binder provider
    // Tiny embedded HTTP server for the Phone ↔ PC browser transfer feature.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Remote storage backends (Twig-style: one Fs interface per source).
    implementation("com.github.mwiede:jsch:0.2.24") {          // SFTP
        // pdfbox already brings bcprov-jdk18on; jsch's older jdk15to18 clashes.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")         // SMB
    // HttpURLConnection cannot send PROPFIND — WebDAV needs a real HTTP client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar") // seekable zstd — Android AAR packs the native .so into jniLibs
    testImplementation("com.github.luben:zstd-jni:1.5.7-3") // plain jar (bundled linux .so) for JVM unit tests
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { // content search: PDF text
        // Ships both bcprov-jdk15to18:1.72 and bcprov-jdk18on:1.76 — keep jdk18on only.
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Ads — PRODUCTION TODO: swap test ad unit IDs for real ones
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    // One-time "remove ads" purchase
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
