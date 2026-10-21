import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.szech.walls"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.szech.walls"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.2"
        resourceConfigurations += listOf("zh", "en")
    }

    // 签名信息从 android/local.properties 读取（该文件不入库，见 local.properties.example）。
    // 没有配置 keystore 时，release 自动退回默认 debug 签名，仍可正常构建。
    val keystoreProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKeystore = keystoreProps.getProperty("walls.storeFile") != null

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("walls.storeFile"))
                storePassword = keystoreProps.getProperty("walls.storePassword")
                keyAlias = keystoreProps.getProperty("walls.keyAlias")
                keyPassword = keystoreProps.getProperty("walls.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Robolectric 需要真实资源（布局 / 主题）才能把 Activity 跑起来
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.yaml:snakeyaml:2.2")
    // 扫码导入 / 生成二维码
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    // 单元测试里 org.json 会被 android.jar 的桩顶掉，显式引一份真的
    testImplementation("org.json:json:20240303")
}

// 把 -Dwalls.* 透传给单元测试进程（真机实测用），并把测试里的 println 显示出来
tasks.withType<Test>().configureEach {
    System.getProperties().forEach { key, value ->
        val k = key.toString()
        if (k.startsWith("walls.")) systemProperty(k, value.toString())
    }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
