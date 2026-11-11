# Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks the declared
dependencies; `make` lists the rest. The tests read the official schema and sample requests from [spec/](../spec)
and verify real playground answers recorded there.

`make live-test` sends three made-up sales to the state's playground with its shared test certificate, which
stores nothing. The playground answers only some networks: a Czech one got through, a cloud CI runner did not,
so CI does not run it.

[llms.txt](../llms.txt) is the setup and the mistakes to avoid, for a coding assistant adding the library to a
project; keep it in step with the docs.

A release is a tag `vX.Y.Z` on `main`, which
[release.yml](../.github/workflows/release.yml) builds and publishes to GitHub Packages.
