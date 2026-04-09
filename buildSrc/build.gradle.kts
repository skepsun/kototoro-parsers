plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

// 移除 jvmToolchain 限制，使用系统默认JDK
// kotlin {
//     jvmToolchain(24)
// }

dependencies {
    implementation(libs.korte)
    implementation(libs.simplexml)
    implementation(libs.kotlinx.coroutines.core)
}

// 统一 Kotlin 字节码目标为 11，避免与依赖消费者（如 Android app）发生 inline 不匹配
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Java 编译目标设为 11，与 Kotlin 保持一致
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}
