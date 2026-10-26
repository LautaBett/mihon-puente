import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.4.21")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.12.0")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
