plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    // 优先使用国内镜像
    google()
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
}
