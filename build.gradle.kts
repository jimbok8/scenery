import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "1.4.10"
    publish
    id("org.jetbrains.dokka") version "1.4.10" //    id("com.github.johnrengelman.shadow") version "6.0.0"
    //    idea
}

repositories {
    mavenCentral()
    jcenter()
    maven("https://jitpack.io")
    maven("https://maven.scijava.org/content/groups/public")
}

dependencies {

    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.0-M1")

    //    implementation(platform("org.scijava:pom-scijava:29.2.1"))
    //    implementation(platform("org.scijava:pom-scijava-base:11.2.0"))
    //    components.all<Rule>()

    sci("org.jogamp.gluegen:gluegen-rt", joglNative)
    sci("org.jogamp.jogl:jogl-all", joglNative)
    sci("org.slf4j:slf4j-api")
    sci("net.clearvolume:cleargl")
    sci("org.joml:joml")
    sci("com.github.scenerygraphics:vector:958f2e6")
    sci("net.java.jinput:jinput:2.0.9", native = "natives-all")
    listOf("scijava-common", "script-editor", "ui-behaviour", "scripting-javascript", "scripting-jython").forEach {
        sci("org.scijava:$it")
    }
    sci("net.sf.trove4j:trove4j")
    sci("net.java.dev.jna:jna")
    sci("net.java.dev.jna:jna-platform:\$jna")
    implementation("org.jocl:jocl:2.0.2")
    implementation(platform("org.lwjgl:lwjgl-bom:3.2.3"))
    listOf("", "-glfw", "-jemalloc", "-vulkan", "-opengl", "-openvr", "-xxhash", "-remotery").forEach {
        implementation("org.lwjgl:lwjgl$it")
        if (it != "-vulkan")
            runtimeOnly("org.lwjgl", "lwjgl$it", classifier = lwjglNatives)
    }
    sci("com.fasterxml.jackson.core:jackson-databind")
    sci("com.fasterxml.jackson.module:jackson-module-kotlin:\$jackson")
    sci("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:\$jackson")
    implementation("graphics.scenery:spirvcrossj:0.7.0-1.1.106.0")
    runtimeOnly("graphics.scenery", "spirvcrossj", classifier = lwjglNatives)
    implementation("org.zeromq:jeromq:0.4.3")
    implementation("com.esotericsoftware:kryo:4.0.2")
    implementation("org.msgpack:msgpack-core:0.8.20")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.8.20")
    implementation("graphics.scenery:jvrpn:1.1.0")
    runtimeOnly("graphics.scenery", "jvrpn", classifier = lwjglNatives)
    sci("io.scif:scifio")
    implementation("org.bytedeco:ffmpeg:4.2.1-1.5.2")
    runtimeOnly("org.bytedeco", "ffmpeg", classifier = ffmpegNatives)
    implementation("org.reflections:reflections:0.9.12")
    implementation("io.github.classgraph:classgraph:4.8.86")
    implementation("sc.fiji:bigvolumeviewer:0.1.8")
//    implementation("org.lwjglx:lwjgl3-awt:0.1.7")
    implementation("com.github.LWJGLX:lwjgl3-awt:cfd741a6")
    sci("org.janelia.saalfeldlab:n5")
    sci("org.janelia.saalfeldlab:n5-imglib2")
    implementation("com.github.kotlin-graphics:assimp:25c68811")

    testSci("junit:junit")
    testSci("org.slf4j:slf4j-simple")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testSci("net.imagej:imagej")
    testSci("net.imagej:ij")
    testSci("net.imglib2:imglib2-ij")
    //    testImplementation("io.kotest:kotest-runner-junit5-jvm:${findProperty("kotestVersion")}")
    //    testImplementation("io.kotest:kotest-assertions-core-jvm:${findProperty("kotestVersion")}")
}

tasks {

    withType<KotlinCompile>().all {
        kotlinOptions {
            jvmTarget = "11"
            freeCompilerArgs += listOf("-Xinline-classes", "-Xopt-in=kotlin.RequiresOptIn")
        }
        sourceCompatibility = "11"
    }
}

//val dokkaJavadocJar by tasks.register<Jar>("dokkaJavadocJar") {
//    dependsOn(tasks.dokkaJavadoc)
//    from(tasks.dokkaJavadoc.get().outputDirectory.get())
//    archiveClassifier.set("javadoc")
//}
//
//val dokkaHtmlJar by tasks.register<Jar>("dokkaHtmlJar") {
//    dependsOn(tasks.dokkaHtml)
//    from(tasks.dokkaHtml.get().outputDirectory.get())
//    archiveClassifier.set("html-doc")
//}
//
//val sourceJar = task("sourceJar", Jar::class) {
//    dependsOn(tasks.classes)
//    archiveClassifier.set("sources")
//    from(sourceSets.main.get().allSource)
//}
//
//artifacts {
//    archives(dokkaJavadocJar)
//    archives(dokkaHtmlJar)
//    archives(sourceJar)
//}