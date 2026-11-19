# Renewing the certificate

A valid certificate renews itself through the CA's API: the request is signed with the certificate being renewed, so
nobody has to log in. The CA asks for it two to three weeks before
expiry; an expired or revoked certificate can only be replaced by the taxpayer in MOJE daně.

```java
Renewal renewal = Renewal.of(Server.PRODUCTION, signer);
UUID id = renewal.request();                                  // store the id
Status status = renewal.status(id);                           // ask again later, by a job
if (status instanceof Status.InProcess p) {
    retryAfter(p.pollAfter());
} else if (status instanceof Status.Issued || status instanceof Status.Delivering) {
    claim(renewal, id);
} else if (status instanceof Status.Rejected r) {
    alert(r.reason());
}                                                             // else Finished: nothing left to do

void claim(Renewal renewal, UUID id) throws IOException {
    Renewed renewed = renewal.claim(id);
    store(renewed.pkcs12(), renewed.password());              // commit it first,
    renewal.acknowledge(id);                                  // then the CA deletes its copy
}
```

`Status` is not sealed either, so a `switch` over it needs a `default`. `Renewal` holds nothing open; its `close()`
is there for the try-with-resources an older version asked for.

`claim` returns the same file again until `acknowledge`, so a crash between the two loses nothing; an issued
certificate nobody claims is gone after 30 days. `notFinished()` finds a request whose id was lost. A non-success
answer is a `RenewalException` with the HTTP status and the CA's code.

The playground has no CA, so its shared certificates cannot be renewed and this part has no live test. Checked on
2026-11-11: the CA's trial host answered a playground certificate's token with 401 "Invalid certificate", and a
request without a token with "No valid authentication method found", so the token was read and the certificate is
what it does not know.
