# The official interface

Files published by the Czech tax administration for developers, unchanged, at
[eet.gov.cz, Pro vývojáře](https://eet.gov.cz/cs/pro-vyvojare/dokumenty-ke-stazeni?category=provyvojare).
Downloaded on 2026-11-10. The tests read them from here, so the library is checked against what the state
published rather than against a copy of it. Each file's source URL is listed in
[cli/spec-check.sh](../cli/spec-check.sh), which the weekly [spec workflow](../.github/workflows/spec.yml) runs to
open an issue when one changes or stops downloading; `make spec-check` runs it by hand.

| File | What |
|---|---|
| `EETServiceSOAP.wsdl`, `EETXMLSchema.xsd` | the service and the message schema, interface 4.1 |
| `requests/CZ*.eet.v4.req.xml` | the state's own signed sample requests, one per playground taxpayer |
| `ca/ca_eet-root_*`, `ca/ca_eet-sub_*` | the CA that issues cash register certificates, playground and production |
| `caeetapi_jwt.yml` | the CA's certificate renewal API, OpenAPI 3.1, unpacked from [caeetapi_jwt.zip](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/caeetapi_jwt.zip) |

The renewal API's token needs `iat` and `exp` at most five minutes apart. The YAML says `{}` is enough; the
binding text,
[CAEET_postupy_zadost_certifikat_v2.pdf](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/CAEET_postupy_zadost_certifikat_v2.pdf),
page 5, requires both, and `Renewal` sends both. The playground has no CA ("CA EET není pro Playground v provozu",
access information, chapter 3.1), so its shared certificates cannot be renewed.

The documents themselves are PDFs on the same page and are not copied here. The ones this library follows:

- [EET_popis_rozhrani_v1.2.pdf](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_popis_rozhrani_v1.2.pdf),
  the interface, binding in Czech; the English
  [v1.2a](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_popis_rozhrani_v1.2a_EN.pdf) is for orientation
- [EET_popis_polozek_datove_zpravy.pdf](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_popis_polozek_datove_zpravy.pdf),
  what each item means and when a sale is registered
- the access and operations information for the
  [playground](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_pristupove_provozni_informace_playgr-3.pdf) and
  [production](https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/EET_pristupove_provozni_informace_produk-2.pdf)

The playground's shared test certificates are not here either: `make playground-certs` downloads them into
`.cache/playground`, which git ignores.

## Recorded answers

`src/test/resources/cz/insuit/eet/playground/` holds real playground responses, recorded by
`make live-test` on 2026-11-10 from a Czech network: an accepted sale, one accepted with warning 6, and a
verification. The confirmations are signed by the tax office's playground certificate
(`CN=NEOSTRE - playground prostredi EET`, `organizationIdentifier=NTRCZ-72080043`), issued by
I.CA Public CA/RSA 06/2022 and valid until 2027-06-08; the tests verify them as of the day they were recorded.
