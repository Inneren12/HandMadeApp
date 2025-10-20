// Hash c961ad5792f71bc7811ca93b6d1306b8
// HandMadeApp: add EXIF dependency for import/preview rotation
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.handmadeapp"
    compileSdk = 36

    testOptions {
       unitTests.isIncludeAndroidResources = true
       unitTests.isReturnDefaultValues = true
   }

    defaultConfig {
        applicationId = "com.handmadeapp"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.01"))

    // 2) Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
        //implementation("androidx.compose.ui:ui-text:1.7.1")// <-- тут живёт KeyboardOptions
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
        //implementation(libs.foundation)
    //implementation(libs.androidx.lifecycle.process)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 3) AndroidX
    // Compose Activity Result launcher
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // AppCompat/Core уже должны быть; на случай отсутствия:
    implementation("androidx.core:core-ktx:1.17.0")
    // Коррутины: перенос тяжёлых шагов S7.* с главного потока
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 4) KotlinX
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // 5) Материалы/совместимость (не обязательно)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Dev prefs (флаги логирования/дампов)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // EXIF: корректный поворот фото
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // (если корутин нет и решишь перейти на coroutines)
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

}

tasks.withType<Test>().configureEach {
    val depsDirProvider = layout.buildDirectory.dir("robolectric-deps")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", depsDirProvider.get().asFile.absolutePath)
    doFirst {
        val androidAll = classpath.files
            .firstOrNull { it.name.startsWith("android-all-instrumented-14-robolectric-10818077-i6") }
            ?: error("android-all-instrumented dependency missing from testRuntimeClasspath")
        project.copy {
            from(androidAll)
            into(depsDirProvider.get())
        }
    }
}
