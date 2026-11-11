# What a merchant keeps an eye on

From the certificate to its renewal: who does each step. Colours: **the taxpayer** in MOJE daně or DIS+, **your
app**, and **this library**.

```mermaid
flowchart TB
    subgraph setup["Once"]
        direction TB
        cert["Download the cash register certificate<br/>.p12 and its password, valid 366 days"]:::taxpayer
        unit["Look up the establishment's number in DIS+"]:::taxpayer
        store["Store the .p12 encrypted, with the unit number"]:::app
        signer["Signer.fromPkcs12<br/>signer.eic() checks whose it is"]:::lib
        cert --> store
        unit --> store
        store --> signer
    end

    subgraph sale["Every payment"]
        direction TB
        contact{"A contact payment?<br/>cash, a terminal, a QR code at the till"}:::app
        none["Not registered"]:::app
        build["Sale.of, Submission.of"]:::lib
        send["EetClient.send<br/>signs, sends, checks the tax office's signature"]:::lib
        result{"Result"}:::lib
        accepted["Accepted: store the POK and the exchange"]:::app
        uncertain["Uncertain: no clear answer<br/>queue it in the database, in the sale's transaction"]:::app
        resend["submission.resend()<br/>new message id, same sale"]:::lib
        rejected["Rejected: the tax office refused the data<br/>fix it and send again"]:::app
        contact -- no --> none
        contact -- yes --> build --> send --> result
        result --> accepted
        result --> uncertain --> resend --> send
        result --> rejected
    end

    subgraph refund["A refund in person"]
        storno["sale.storno<br/>a sale of its own, amounts negated"]:::lib
    end

    subgraph expiry["Before the certificate expires"]
        direction TB
        watch["signer.notAfter()"]:::lib
        warn["Warn the merchant, start renewal<br/>2 to 3 weeks ahead"]:::app
        renew["Renewal: request, status, claim"]:::lib
        keep["Store the new .p12, then acknowledge<br/>close the old EetClient, a new Signer"]:::app
        expired["Expired or revoked:<br/>a new one from MOJE daně, uploaded again"]:::taxpayer
        held["Sales come back Uncertain, Reason.CERTIFICATE:<br/>held and sent once a valid one signs"]:::lib
        watch --> warn --> renew --> keep
        keep ~~~ expired
        watch -. too late .-> expired
        expired -.-> held
    end

    signer --> contact
    storno --> build
    accepted ~~~ watch

    classDef taxpayer fill:#fde2b8,stroke:#b7791f,color:#1a1a1a
    classDef app fill:#dbeafe,stroke:#2b6cb0,color:#1a1a1a
    classDef lib fill:#d1fae5,stroke:#2f855a,color:#1a1a1a
```

The details: [using the client](usage.md) for sending and resends, [renewing the certificate](renewal.md) for the
CA's API.
