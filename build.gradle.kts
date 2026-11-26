plugins {
  application
}

group = "dev.draftwatch"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

// Jackson 2.x: Jackson 3.x requires Java 17.
val jacksonVersion = "2.22.3"

dependencies {
  implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
  testImplementation("junit:junit:4.13.2")
}

// Compile and test with a locally installed JDK 11; no auto-provisioning.
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
}

application {
  mainClass.set("dev.draftwatch.app.Main")
  applicationName = "draftwatch"
}

// The evaluation's scoring tool (docs/EVALUATION.md, "Scoring the detectors"): its own source set,
// which depends on main and never the other way round. Its tests run in the main test task.
val scoring: SourceSet by sourceSets.creating {
  compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
  runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

sourceSets.test {
  compileClasspath += scoring.output
  runtimeClasspath += scoring.output
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// The reference harness's unit tests: standard library only, no vLLM, no GPU.
val pythonTest by tasks.registering(Exec::class) {
  description = "Runs the reference harness's Python unit tests."
  group = "verification"
  workingDir = projectDir
  commandLine("python3", "-m", "unittest", "discover", "-s", "python/tests", "-q")
}

// The evaluation tooling's unit tests: standard library only (docs/EVALUATION.md).
val evaluationTest by tasks.registering(Exec::class) {
  description = "Runs the evaluation tooling's Python unit tests."
  group = "verification"
  workingDir = projectDir
  commandLine("python3", "-m", "unittest", "discover", "-s", "evaluation/tests", "-q")
}

tasks.named("check") {
  dependsOn(pythonTest, evaluationTest)
}

// Runs the scoring tool. The tool records the commit it ran at, so the task passes it in.
tasks.register<JavaExec>("score") {
  description = "Runs the evaluation scoring tool (export, score)."
  group = "evaluation"
  mainClass.set("dev.draftwatch.scoring.ScoreMain")
  classpath = scoring.runtimeClasspath
  workingDir = projectDir
  javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}
