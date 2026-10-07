plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "net.ithandsfree.softphone"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("net.java.dev.jna:jna:5.15.0")
    testImplementation(kotlin("test"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("net.ithandsfree.softphone.win.PhoneAppKt")
}

val phoneFlavor = (findProperty("flavor") as String?) ?: "ihf"

fun JavaExec.phoneRuntime() {
    val nativeDir = project.layout.projectDirectory.dir("native/out").asFile
    systemProperty("jna.library.path", nativeDir.absolutePath)
    val path = System.getenv("PATH").orEmpty()
    environment("PATH", nativeDir.absolutePath + ";" + path)
}

tasks.named<JavaExec>("run") {
    phoneRuntime()
    systemProperty("ihf.flavor", phoneFlavor)
}

tasks.register<JavaExec>("runCommunity") {
    group = "application"
    description = "Community Softphone. The BFF URL and SIP domain are entered in the window."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    phoneRuntime()
    systemProperty("ihf.flavor", "community")
}

tasks.test {
    useJUnitPlatform()
}
