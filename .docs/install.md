# Installing

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/landsman/eet-client")
        credentials {
            username = providers.gradleProperty("gpr.user").orElse("gh").orNull
            password = providers.gradleProperty("gpr.key").orElse(gh("auth", "token")).orNull
        }
        content { includeGroup("cz.insuit") }
    }
}

dependencies {
    implementation("cz.insuit:eet-client:0.2.0")
}

// gh's token, or nothing when gh is missing or signed out: the build then gets GitHub's 401, not a crash.
fun gh(vararg args: String): Provider<String> =
    provider {
        runCatching {
            providers.exec {
                commandLine("gh", *args)
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
```

**GitHub Packages asks for a token even for a public package**, with the `read:packages` scope; any non-empty
user name passes. With the [GitHub CLI](https://cli.github.com/) signed in, the build above reads gh's own token,
so no copy sits on disk. Give gh the scope once:

```sh
gh auth refresh -h github.com -s read:packages
```

- **Without gh**, a personal access token (classic) with only `read:packages` as `gpr.user` and `gpr.key` in
  `~/.gradle/gradle.properties`, never in the project. Fine-grained tokens cannot read packages.
- **In CI**, put `.orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))` before the gh fallback.
- **The configuration cache** stores the resolved token, encrypted, under the project's `.gradle`.

<details><summary>Maven</summary>

```xml
<repositories>
    <repository>
        <id>github-eet-client</id>
        <url>https://maven.pkg.github.com/landsman/eet-client</url>
    </repository>
</repositories>

<dependency>
    <groupId>cz.insuit</groupId>
    <artifactId>eet-client</artifactId>
    <version>0.2.0</version>
</dependency>
```

and in `~/.m2/settings.xml` a `<server>` with the same `id`, your GitHub user and the token.

</details>

Only tagged releases are published. `make publish-local` in a checkout installs the next snapshot into `~/.m2`,
where `mavenLocal()` finds it.

## With a coding assistant

Paste this into it:

```text
Add the EET 2.0 client to this Java or Kotlin project.

1. Read https://github.com/landsman/eet-client/blob/main/llms.txt and https://github.com/landsman/eet-client/blob/main/.docs/usage.md first. Do not guess the API.
2. Dependency: cz.insuit:eet-client:0.2.0 from GitHub Packages, as https://github.com/landsman/eet-client/blob/main/.docs/install.md shows. Never write a token into the project.
3. Certificate: each taxpayer's .p12 and its password are stored encrypted, never in the repository; load them with Signer.fromPkcs12.
4. Code: one EetClient per certificate, Environment.PLAYGROUND until I say production. Amounts are haléře as long.
5. Persist every Submission and its Result in the same transaction as the sale. An Uncertain result is queued in the
   database and sent again with submission.resend() until it is Accepted; nothing is retried in memory.
```
