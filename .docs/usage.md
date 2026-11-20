# Using the client

```java
Signer signer = Signer.fromPkcs12(Files.newInputStream(p12), password);
EetClient eet = EetClient.builder(Environment.PRODUCTION, signer).build();

Sale sale = Sale.of("CZ00000019", 11, "till-1", "2027/000123", paidAt, 12350); // 123.50 Kč
Submission submission = Submission.of(sale);
Result result = eet.send(submission);

SaleState state = result.match(
        a -> registered(a.pok(), a.exchange()),                       // registered
        v -> SaleState.CHECKED,                                       // a dry run passed
        r -> refused(r.code(), r.message()),                          // fix the message
        u -> queued(submission.resend()));                            // nobody knows yet
```

`match` takes one function per kind, so the compiler checks that all four are handled, on Java 8 as on 21 and in
Kotlin, and a kind added later breaks the build instead of slipping past. Each function returns a value. Code that
only acts, or throws a checked exception, can use an `instanceof` chain instead, which nothing checks; `Result` is
not sealed, so a `switch` over it needs a `default`.

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
negated. One `EetClient` per certificate.

The certificate is the taxpayer's: they download it as a `.p12` with a password from MOJE daně, and it is valid
for 366 days. `Signer.of(key, certificate)` takes a key held somewhere else.

The answer is trusted only when it is signed over its Body by a certificate of the tax office
(`organizationIdentifier=NTRCZ-72080043`) issued under I.CA's root, valid at the time of reading. Revocation is not
checked, so reading an answer makes no network call.

The connection is the JDK's `HttpURLConnection`, so the JVM's settings for it apply here too: the proxy properties,
`http.keepAlive`, and `http.maxConnections`, which keeps at most 5 idle connections per host. When sending for many
merchants at once, raise it, or every send past the fifth at a time opens a connection with a new TLS handshake.
`HttpsURLConnection`'s JVM-wide trust settings do not apply: each connection checks the certificate and the host name
as the JDK does by default. The timeout covers connecting and then each read, not the name lookup. An interrupt does
not stop a send on a platform thread, which waits out its timeouts; on a virtual thread it closes the socket, and the
send comes back `Uncertain` with `Reason.NETWORK`.

## Logging, and what support asks for

The client logs nothing. Log `result.toString()`: the kind, the code or reason with the tax office's text, and the
exchange's two ids, without the bodies, which run to kilobytes and belong in the database with the result. Control
characters in it are escaped, so an answer cannot start a log line of its own.

The two ids are what to give the tax office about one attempt: `messageId`, the `uuid_zpravy` that was sent, and
`transactionId`, the `X-Global-Transaction-Id` the service answered with, which its documentation names as the
handle for support. An attempt that got no answer, a timeout or a failed connection, has only the `messageId`.
