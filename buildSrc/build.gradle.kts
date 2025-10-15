plugins {
    `kotlin-dsl`
}

repositories {
    // 优先使用国内镜像以避免 TLS 握手问题
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    maven("https://maven.aliyun.com/repository/public")
    google()
    maven("https://maven.aliyun.com/repository/google")
    mavenCentral()
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    implementation(libs.korte)
    implementation(libs.simplexml)
    implementation(libs.kotlinx.coroutines.core)
}
