# eet-client

A Java client for [EET 2.0](https://eet.gov.cz/cs/pro-vyvojare), the Czech electronic sales registration that
starts on 2027-01-01. It builds the message, signs it with the taxpayer's certificate, sends it, checks the tax
office's signature on the answer and says what came of it. It needs Java 21; besides the JDK it uses only jackson-core, for the renewal API.

Checked against the state's playground. Published on GitHub Packages, not Maven Central.

## Install

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

1. Read https://github.com/landsman/eet-client/blob/main/llms.txt and https://github.com/landsman/eet-client/blob/main/README.md first. Do not guess the API.
2. Dependency: cz.insuit:eet-client:0.2.0 from GitHub Packages, as the README's Install section shows. Never write a token into the project.
3. Certificate: each taxpayer's .p12 and its password are stored encrypted, never in the repository; load them with Signer.fromPkcs12.
4. Code: one EetClient per certificate, Environment.PLAYGROUND until I say production. Amounts are haléře as long.
5. Persist every Submission and its Result in the same transaction as the sale. An Uncertain result is queued in the
   database and sent again with submission.resend() until it is Accepted; nothing is retried in memory.
```

## Use

```java
Signer signer = Signer.fromPkcs12(Files.newInputStream(p12), password);
EetClient eet = EetClient.builder(Environment.PRODUCTION, signer).build();

Sale sale = Sale.of("CZ00000019", 11, "till-1", "2027/000123", paidAt, 12350); // 123.50 Kč
Submission submission = Submission.of(sale);
Result result = eet.send(submission);

switch (result) {
    case Result.Accepted a -> store(a.pok(), a.exchange());          // registered
    case Result.Uncertain u -> retryLater(submission.resend());      // nobody knows yet
    case Result.Rejected r -> alert(r.code(), r.message());           // fix the message
    case Result.Verified v -> {}                                      // a dry run passed
}
```

## What it does and what it leaves to you

The client sends one attempt per call and never retries by itself. An uncertain result (a timeout, error -1, an
answer whose signature does not check out) is sent again with `submission.resend()`: a new message id, the same
sale. The law wants that done without undue delay once the cause is gone, within 48 hours of the sale and after
that too, so the queue belongs in your database, next to the sale. Every accepted attempt gets its own POK, a
resend of an accepted sale included; keep each one, with the `exchange()` that carries the signed request.

Deciding *which* payments are registered is yours too. Since EET 2.0 it is a contact payment that counts, not the
instrument: cash, a card terminal or a QR code paid at the till are registered, a card payment on a website or a
transfer from home is not. A refund is a negative sale of its own. The
[item descriptions](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_popis_polozek_datove_zpravy.pdf)
go through the cases.

A sale the certificate cannot sign, because it expired or is another taxpayer's, is not sent: it comes back
`Uncertain` with `Reason.CERTIFICATE`, to be held and sent once a valid certificate signs it, rather than refused for
good by the tax office. `signer.eic()` and `signer.notAfter()` read the certificate for the checks and warnings of your
own. A refund handed back in person is `sale.storno(sequenceNumber, time)`, a sale of its own with every amount
negated. One `EetClient` per certificate; `close()` it when the certificate is replaced.

The certificate is the taxpayer's: they download it as a `.p12` with a password from MOJE daně, and it is valid
for 366 days. `Signer.of(key, certificate)` takes a key held somewhere else.

The answer is trusted only when it is signed over its Body by a certificate of the tax office
(`organizationIdentifier=NTRCZ-72080043`) issued under I.CA's root, valid at the time of reading. Revocation is not
checked, so reading an answer makes no network call.

## Renewing the certificate

A valid certificate renews itself through the CA's API: the request is signed with the certificate being renewed, so
nobody has to log in. The CA asks for it two to three weeks before
expiry; an expired or revoked certificate can only be replaced by the taxpayer in MOJE daně.

```java
try (Renewal renewal = Renewal.of(Server.PRODUCTION, signer)) {
    UUID id = renewal.request();                              // store the id
    switch (renewal.status(id)) {                             // ask again later, by a job
        case Status.InProcess p -> retryAfter(p.pollAfter());
        case Status.Issued i -> claim(renewal, id);
        case Status.Delivering d -> claim(renewal, id);
        case Status.Finished f -> {}
        case Status.Rejected r -> alert(r.reason());
    }
}

void claim(Renewal renewal, UUID id) throws IOException {
    Renewed renewed = renewal.claim(id);
    store(renewed.pkcs12(), renewed.password());              // commit it first,
    renewal.acknowledge(id);                                  // then the CA deletes its copy
}
```

`claim` returns the same file again until `acknowledge`, so a crash between the two loses nothing; an issued
certificate nobody claims is gone after 30 days. `notFinished()` finds a request whose id was lost. A non-success
answer is a `RenewalException` with the HTTP status and the CA's code.

The playground has no CA, so its shared certificates cannot be renewed and this part has no live test. Checked on
2026-11-11: the CA's trial host answered a playground certificate's token with 401 "Invalid certificate", and a
request without a token with "No valid authentication method found", so the token was read and the certificate is
what it does not know.

## Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks the declared
dependencies; `make` lists the rest. The tests read the official schema and sample requests from [spec/](spec)
and verify real playground answers recorded there.

`make live-test` sends three made-up sales to the state's playground with its shared test certificate, which
stores nothing. The playground answers only some networks: a Czech one got through, a cloud CI runner did not,
so CI does not run it.

[llms.txt](llms.txt) is the setup and the mistakes to avoid, for a coding assistant adding the library to a
project; keep it in step with this README.

A release is a tag `vX.Y.Z` on `main`, which
[release.yml](.github/workflows/release.yml) builds and publishes to GitHub Packages.
