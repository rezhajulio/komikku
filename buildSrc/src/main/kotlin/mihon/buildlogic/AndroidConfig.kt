package mihon.buildlogic

import org.gradle.api.JavaVersion as GradleJavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget

object AndroidConfig {
    // 37: ca.mpreg:imagedecoder (Foldchiyomi WebGPU viewer) requires compiling
    // against API 37+. Target stays 36, matching the Foldchiyomi reference
    // (android-sdk-compile = "37.2", android-sdk-target = "36").
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 26

    // https://youtrack.jetbrains.com/issue/KT-66995/JvmTarget-and-JavaVersion-compatibility-for-easier-JVM-version-setup
    val JavaVersion = GradleJavaVersion.VERSION_17
    val JvmTarget = KotlinJvmTarget.JVM_17
}
