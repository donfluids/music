import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing: put a keystore.properties next to settings.gradle.kts with
// storeFile, storePassword, keyAlias and keyPassword. Without it the release APK is unsigned.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    namespace = "com.donfluids.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.donfluids.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A debug build is debuggable and signed with the shared debug key, so it must
            // never be mistaken for, or install over, the release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":analysis"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Screenshots of the screen, rendered on the build machine (Robolectric) and saved as PNGs (Roborazzi).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.org.json)
}

// Forward -Pclips.dir=<folder> to the test JVM so ClipReplayTest can find recorded clips.
val clipsDir = providers.gradleProperty("clips.dir")
tasks.withType<Test>().configureEach {
    if (clipsDir.isPresent) systemProperty("clips.dir", clipsDir.get())
}

// Robolectric's Android system jar for the screenshot tests, fetched by Gradle (with its retries
// and cache) instead of by Robolectric at test time, then used offline.
val robolectricAndroidAll: Configuration by configurations.creating
dependencies { robolectricAndroidAll("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7") }
val robolectricJars = tasks.register<Copy>("robolectricJars") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-jars"))
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
}
// The test host activity comes from ui-test-manifest, which only the debug build carries.
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach {
    (this as Test).filter.excludeTestsMatching("*.ScreenshotTest")
}
