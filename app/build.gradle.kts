plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "cn.linkvault"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.linkvault"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.6.0"
    }
    buildFeatures { compose = true }
    // 迁移测试直接读取已版本化的历史 schema，避免遗漏或维护两份副本。
    sourceSets.getByName("test").resources.srcDir("schemas")
    // 发布包沿用与调试包同一把签名钥匙，这样已装在手机上的版本能直接覆盖升级
    // （换钥匙会让老用户只能卸载重装、连带丢光收藏）。
    // 钥匙由 Docker 卷 link-vault-debug-signing 挂在 /root/.android 下提供；
    // 换机器先 ./signing-key.sh restore，或用下面这几个环境变量指到别处。
    signingConfigs {
        create("release") {
            val home = System.getProperty("user.home")
            storeFile = file(System.getenv("VAULT_KEYSTORE") ?: "$home/.android/debug.keystore")
            storePassword = System.getenv("VAULT_STOREPASS") ?: "android"
            keyAlias = System.getenv("VAULT_KEYALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("VAULT_KEYPASS") ?: "android"
        }
    }
    buildTypes {
        release {
            // 版本追溯由发布标签绑定提交；不嵌入构建前的旧 HEAD，保证审核后的同源码产物可复现。
            vcsInfo { include = false }
            // 调试包留着 adb 读私有目录的通道，发布包必须关掉；顺带打开代码与资源收缩。
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("vault.screenshots", providers.gradleProperty("vaultScreenshots")
                .getOrElse("${rootProject.projectDir}/deliverables/screenshots"))
        }
    }
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
