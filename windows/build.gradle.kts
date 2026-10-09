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
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // Round-3 desktop design: custom title bar with Windows 11 snap layouts, rounded controls, SVG icons.
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.formdev:flatlaf-extras:3.5.4")
    implementation("com.github.weisj:jsvg:1.6.1")
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
    // Design review: gradlew run -Ppreview=incoming
    (findProperty("preview") as String?)?.let { systemProperty("ihf.preview", it) }
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
