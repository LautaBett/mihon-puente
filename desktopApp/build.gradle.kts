import org.gradle.api.plugins.JavaApplication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "application")

configure<JavaApplication> {
    mainClass.set("app.mihon.puente.desktop.DesktopAppKt")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    add("implementation", project(":core"))
}
