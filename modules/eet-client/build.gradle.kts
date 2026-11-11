plugins {
    `java-library`
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
val spec = rootProject.layout.projectDirectory.dir("spec")

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
        rootProject.layout.projectDirectory
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
