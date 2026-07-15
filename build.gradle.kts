plugins {
    java
}

val dyn4jVersion: String by project
val libgdxVersion: String by project
val imguiVersion: String by project
val junitVersion: String by project
val slf4jVersion: String by project

subprojects {
    apply(plugin = "java")

    group = "dev.suika"
    version = "0.18.0"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:${junitVersion}")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("--enable-preview")
        options.release.set(21)
    }
}
