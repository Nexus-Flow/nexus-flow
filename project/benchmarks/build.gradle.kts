/*
 * JMH benchmarks subproject.
 *
 * Goals:
 *   - DispatchPlan lookup cost: ClassValue-cached snapshot vs ConcurrentHashMap.get.
 *   - TypeReference#hashCode cached at construction.
 *   - Event fan-out via DefaultEventBus#dispatchResult at 1, 10, 100
 *     listeners.
 *
 * We do NOT pull in the third-party me.champeau.jmh plugin to keep
 * the build hermetic. Instead, JMH is added as a regular dependency
 * and a `jmh` JavaExec task runs `org.openjdk.jmh.Main` against the
 * compiled main classes (no annotation-processor codegen step;
 * benchmarks are written against the public JMH runtime API).
 */
plugins {
    java
}

repositories {
    mavenLocal()
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

dependencies {
    implementation(project(":project:core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)
}

tasks.withType<JavaCompile>().configureEach {
    // The benchmarks need access to package-private (default) types
    // such as DefaultEventBus and the fire-and-forget event publisher
    // facade kept around as a baseline. The benchmark sources live in
    // the same package paths as the targets, so they need
    // --enable-preview just like core.
    options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:none"))
    options.release.set(25)
}

/**
 * Primary JMH entry point. Runs every JMH benchmark in the
 * `org.openjdk.jmh.runner.Main` style. Outputs to
 * `build/reports/jmh/results.txt`. The `args` list is the same
 * argv array JMH accepts on the command line; override on invocation:
 *   ./gradlew :project:benchmarks:jmh --args="-i 3 -wi 3 -f 1 ..."
 */
val jmhResultsFile = layout.buildDirectory.file("reports/jmh/results.txt")
val jmhResultsDir = layout.buildDirectory.dir("reports/jmh")

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Run JMH benchmarks. Defaults: 3 warmup / 3 measurement iterations, 1 fork."
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-preview")
    outputs.dir(jmhResultsDir)
    args(
        "-wi", "3",
        "-i", "3",
        "-f", "1",
        "-r", "1s",
        "-w", "1s",
        "-rf", "TEXT",
        "-rff", jmhResultsFile.get().asFile.absolutePath
    )
}


