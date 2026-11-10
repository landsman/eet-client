# eet-client

A Java client for [EET 2.0](https://eet.gov.cz/cs/pro-vyvojare), the Czech electronic sales registration that
starts on 2027-01-01. It builds the message, signs it with the taxpayer's certificate, sends it, checks the tax
office's signature on the answer and says what came of it. It needs Java 21 and nothing but the JDK.

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

The certificate is the taxpayer's: they download it as a `.p12` with a password from MOJE daně, and it is valid
for 366 days. `Signer.of(key, certificate)` takes a key held somewhere else.

The answer is trusted only when it is signed over its Body by a certificate of the tax office
(`organizationIdentifier=NTRCZ-72080043`) issued under I.CA's root, valid at the time of reading. Revocation is not
checked, so reading an answer makes no network call.

## Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks the declared
dependencies; `make` lists the rest. The tests read the official schema and sample requests from [spec/](spec)
and verify real playground answers recorded there.

`make live-test` sends three made-up sales to the state's playground with its shared test certificate, which
stores nothing. The playground answers only some networks: a Czech one got through, a cloud CI runner did not,
so CI does not run it.

A release is a tag `vX.Y.Z` on `main`, which
[release.yml](.github/workflows/release.yml) builds and publishes to GitHub Packages.
