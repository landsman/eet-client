.DEFAULT_GOAL := help

.PHONY: help
help: ## show this help
	@awk 'BEGIN {FS = ":.*##"; print "usage: make <target>\n"} \
		/^##@/ { printf "\n\033[1m%s:\033[0m\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_.-]+:.*##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

##@ Build
.PHONY: build test lock publish publish-local
build: ## check formatting, compile with error prone and nullaway, test, check declared dependencies and package the jar; TESTS_JDK=21 tests on that JDK
	./gradlew build projectHealth $(if $(TESTS_JDK),-PtestsJdk=$(TESTS_JDK))

test: ## run the tests
	./gradlew test

lock: ## rewrite modules/eet-client/gradle.lockfile after a dependency changes, the vulnerability scan reads it
	./gradlew :modules:eet-client:dependencies --configuration runtimeClasspath --write-locks

publish: ## build and publish the jar to GitHub Packages, as the release workflow does; RELEASE_VERSION=v0.1.0 releases
	./gradlew build publish $(if $(RELEASE_VERSION),-PreleaseVersion=$(RELEASE_VERSION:v%=%))

publish-local: ## install the jar into ~/.m2, to try it in another project without a token
	./gradlew publishToMavenLocal

##@ Playground
# The state's shared test certificates, published on eet.gov.cz for every tester; kept out of git all the same.
PLAYGROUND_ZIP = https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare/CAEET_Playground_2026_v1.zip

.PHONY: playground-certs live-test
playground-certs: ## download the shared playground certificates into .cache/playground
	mkdir -p .cache/playground
	curl -fsSL -o .cache/playground/certs.zip $(PLAYGROUND_ZIP)
	unzip -o -q .cache/playground/certs.zip -d .cache/playground

live-test: playground-certs ## send made-up sales to the playground; it answers only some networks, a Czech one did
	./gradlew :modules:eet-client:test -Plive --tests '*PlaygroundTest' --rerun

##@ Code style
.PHONY: format lint
format: ## reformat java and gradle files in place
	./gradlew spotlessApply

lint: ## fail on formatting that make format would change
	./gradlew spotlessCheck
