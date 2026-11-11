import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.11.0"
    id("com.autonomousapps.dependency-analysis") version "3.20.1"
    id("net.ltgt.errorprone") version "5.2.0"
}

// Compiled once, for release 21, on whatever JDK runs Gradle; the tests run on the JDK -PtestsJdk names, so CI's
// matrix checks the jar on each JDK it supports. Without it they run on Gradle's own.
val testsJdk = providers.gradleProperty("testsJdk")

// A tag vX.Y.Z releases X.Y.Z (-PreleaseVersion, set by `make publish`); anything else is the next snapshot.
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.4.0-SNAPSHOT")

group = "cz.insuit"
version = releaseVersion.get()

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    // Built on any current JDK, usable from Java 21.
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (testsJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testsJdk.get()) }
    }
}

// What ships is held to the strictest checks: every warning fails, and the JSpecify annotations are enforced by
// NullAway rather than left as documentation.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:OnlyNullMarked", "true")
    }
}

tasks.withType<Javadoc>().configureEach {
    // An @param per record component would only repeat its name; broken links still fail.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

description = "A client for EET 2.0, the Czech electronic sales registration"

dependencies {
    // Nullability annotations are part of the API, so Kotlin callers see String and String? correctly.
    // SOAP, XML signatures, PKCS#12 and HTTP come with the JDK; JSON, for the CA's renewal API, does not. Only the
    // streaming core, so a caller on Jackson 3 or none gets one small jar and no ObjectMapper of ours.
    api("org.jspecify:jspecify:1.0.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.4")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    // Pinned, so a JPMS caller's `requires` does not change with the jar's file name.
    manifest { attributes("Automatic-Module-Name" to "cz.insuit.eet") }
}

// The official WSDL, schema and sample requests live in spec/ at the root, outside any source set, so the tests
// read them from there rather than from a copy that could drift.
val spec = layout.projectDirectory.dir("spec")

// A signing key for the tests, made by keytool at build time, so no private key is ever committed. CN is a
// playground EIČ, as a real cash register certificate carries the taxpayer's.
val testKeystore = layout.buildDirectory.file("test-keystore/test.p12")
val makeTestKeystore =
    tasks.register<Exec>("makeTestKeystore") {
        description = "Generates the PKCS#12 signing key the tests use."
        val out = testKeystore.get().asFile
        outputs.file(out)
        doFirst {
            out.parentFile.mkdirs()
            out.delete()
        }
        // The keytool of the JDK running Gradle, which every build has.
        commandLine(
            File(System.getProperty("java.home"), "bin/keytool").absolutePath,
            "-genkeypair",
            "-keystore",
            out.absolutePath,
            "-storetype",
            "PKCS12",
            "-storepass",
            "test-password",
            "-alias",
            "eet",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-sigalg",
            "SHA256withRSA",
            "-validity",
            "3650",
            "-dname",
            "CN=CZ00000019, C=CZ",
        )
    }

// The live playground test sends one made-up sale to the state's playground, which only answers some networks; it
// runs only with -Plive (make live-test), never in make build.
val live = providers.gradleProperty("live").isPresent

tasks.test {
    dependsOn(makeTestKeystore)
    // Read through system properties, which Gradle does not look inside: a changed spec file reruns the tests.
    inputs.dir(spec).withPropertyName("spec")
    systemProperty("eet.spec", spec.asFile.absolutePath)
    systemProperty("eet.testKeystore", testKeystore.get().asFile.absolutePath)
    systemProperty(
        "eet.playground",
        layout.projectDirectory
            .dir(".cache/playground")
            .asFile.absolutePath,
    )
    useJUnitPlatform { if (!live) excludeTags("live") }
}

// Trivy reads Gradle only through gradle.lockfile. Only what ships is locked.
configurations.named("runtimeClasspath") {
    resolutionStrategy.activateDependencyLocking()
}

// Nothing else in the build resolves runtimeClasspath, so a stale lockfile would pass and Trivy would scan
// versions that do not ship. Resolving it here fails the build on any drift; `make lock` rewrites the file.
val verifyLocks =
    tasks.register("verifyLocks") {
        description = "Fails when gradle.lockfile no longer matches the runtime classpath."
        group = "verification"
        inputs.files(configurations.runtimeClasspath)
        doLast {}
    }

tasks.check {
    dependsOn(verifyLocks)
}

dependencies {
    "errorprone"("com.google.errorprone:error_prone_core:2.52.0")
    "errorprone"("com.uber.nullaway:nullaway:0.14.3")
}

// Published to this repository's GitHub Packages; a vX.Y.Z tag cuts a release (.github/workflows/release.yml).
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = project.description
                url = "https://github.com/landsman/eet-client"
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "landsman"
                        name = "Michal Landsman"
                    }
                }
                scm {
                    url = "https://github.com/landsman/eet-client"
                    connection = "scm:git:https://github.com/landsman/eet-client.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/landsman/eet-client")
            // Set by GitHub Actions; a release from a laptop is not the way this one is cut.
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// make build fails on a dependency the library declares and does not use, or uses without declaring: the first ships
// in the POM to every caller for nothing, the second breaks the day the dependency that brought it drops it.
dependencyAnalysis {
    structure {
        // junit-jupiter is the aggregate that brings the engine; the advice to declare -api instead would leave the
        // tests compiling and none of them running.
        bundle("junit-jupiter") { includeGroup("org.junit.jupiter") }
    }
    issues { all { onAny { severity("fail") } } }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.103.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
