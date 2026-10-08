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

tasks.named("check") {
  dependsOn(pythonTest)
}
