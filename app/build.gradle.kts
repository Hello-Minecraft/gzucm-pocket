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
        // 1.2.5 → 10205。改版本名必须同步改 code，VersionTest 会校验。
        versionCode = 10502
        versionName = "1.5.2"
        resourceConfigurations += listOf("zh", "en")

        /*
         * 和风天气的 key / host。
         *
         * **不写进仓库**：仓库是公开的，key 一旦提交等于把免费额度公开。
         * 从根目录 weather.properties（已 gitignore）读；文件不存在时留空，
         * 天气卡整块消失，其余功能不受影响（见 core/Weather.kt 的说明）。
         *
         *   key=你的和风 API Key
         *   host=https://xxxxx.re.qweatherapi.com     # 新账号的专属 host，可省略
         */
        val weatherProps = Properties().apply {
            val f = rootProject.file("weather.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "WEATHER_KEY",
            "\"${weatherProps.getProperty("key", "").trim()}\"",
        )
        buildConfigField(
            "String",
            "WEATHER_HOST",
            "\"${weatherProps.getProperty("host", "https://devapi.qweather.com").trim()}\"",
        )
        // 阿里云 FC 转发地址（推荐用法）：填了它 App 就只跟自己的函数说话，
        // 不再直连和风、也不碰私钥 —— 见 weather.properties 里的说明
        buildConfigField(
            "String",
            "WEATHER_BASE",
            "\"${weatherProps.getProperty("base", "").trim()}\"",
        )
        buildConfigField(
            "String",
            "WEATHER_APP_TOKEN",
            "\"${weatherProps.getProperty("app_token", "").trim()}\"",
        )
        /*
         * 班级日程云函数（2026-11-22）。
         *
         * 和天气**不是同一个函数**：天气只转发和风、班级日程读写 OSS，
         * 两套逻辑放一个文件里只会互相牵连，所以各建各的（同为 cn-hangzhou）。
         * 密钥复用 APP_TOKEN 那一个，不另开一套 —— 多一个密钥就多一处要维护、
         * 多一处可能泄露。
         *
         * 留空时班级日程整块不出现（不是报错），和天气缺失时的处理一致。
         */
        buildConfigField(
            "String",
            "CLASSPLAN_BASE",
            "\"${weatherProps.getProperty("classplan_base", "").trim()}\"",
        )
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

            /*
             * Robolectric 的临时目录改到 D 盘。
             *
             * C 盘只有 89G 且已满（剩 300 多 MB），每次跑截图测试要往
             * %TEMP% 写几百 MB 的 native runtime 和模拟器镜像，
             * 写不进去就整片失败 —— 报的是「磁盘空间不足」，
             * 看起来像代码坏了，其实是环境问题。
             */
            all {
                it.systemProperty("java.io.tmpdir", "D:/App/_android/tmp")
            }
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

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    /*
     * WebView 的「算法暗化」开关。
     *
     * Android 13 起 WebView 默认跟随系统深色，把浅色网页强行反色 ——
     * 学校的实验室平台、智慧宿管在深色模式下直接变成黑底黑字/白屏
     * （2026-11-21 实测）。这个库的 WebSettingsCompat.setAlgorithmicDarkeningAllowed
     * 是唯一能在 API 33+ 关掉它的正规入口（旧的 settings.forceDark 已经废弃且无效）。
     */
    implementation("androidx.webkit:webkit:1.12.1")
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
