import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gzucm.pocket"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gzucm.pocket"
        minSdk = 26
        targetSdk = 35
        // versionCode 由 versionName 推出：主*10000 + 次*100 + 修订
        // 1.2.1 → 10201。改版本名必须同步改 code，VersionTest 会校验。
        versionCode = 10201
        versionName = "1.2.1"
        resourceConfigurations += listOf("zh", "en")
    }

    /**
     * 签名配置。
     *
     * **密钥和密码不进仓库** —— 从根目录的 keystore.properties 读，
     * 那个文件在 .gitignore 里。没有它（比如别人 clone 下来）就用 debug 签名，
     * 保证能直接构建。
     */
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasKeystore = !keystoreProps.getProperty("storeFile").isNullOrBlank() &&
        rootProject.file(keystoreProps.getProperty("storeFile")!!).exists()

    signingConfigs {
        if (hasKeystore) {
            create("stable") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // ???????????orkManager ????????????????
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ??????????backdrop blur??????????????????????
    // ??? 1.6.10??.7+ ?????Compose Multiplatform 1.12?????AGP 8.6+??
    implementation("dev.chrisbanes.haze:haze:1.6.10")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // LazyColumn ?????????????????????????????
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.compose.runtime:runtime-saveable")
    testImplementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
