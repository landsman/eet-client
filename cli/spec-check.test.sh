#!/usr/bin/env bash
# What spec-check.sh would get wrong silently: a download that failed read as "no change", a changed file inside the
# renewal API's zip missed, a second issue opened every week for the same change. eet.gov.cz is a stub serving copies
# of spec/, and gh a stub that logs its calls.
set -uo pipefail

script="$(cd "$(dirname "$0")" && pwd)/spec-check.sh"
root="$(cd "$(dirname "$0")/.." && pwd)"
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
fails=0
# Each prints "ok   <behaviour>" or "FAIL <behaviour>" and keeps going; the last line exits with the count.
verdict() { if [ "$2" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
exits() { [ "$rc" -eq "$2" ]; verdict "$1" $?; }
says() { grep -q "$2" <<<"$out"; verdict "$1" $?; }
omits() { ! grep -q "$2" <<<"$out"; verdict "$1" $?; }
logged() { grep -q "$2" "$GH_LOG"; verdict "$1" $?; }

mkdir -p "$t/bin" "$t/site"
# curl -fsSL -o <out> <url>: copies the file the url names from $t/site, or fails as curl -f does on a 404.
cat >"$t/bin/curl" <<'STUB'
#!/usr/bin/env bash
out=$3; url=$4
src="$SITE/${url##*/}"
[ -f "$src" ] || exit 22
cp "$src" "$out"
STUB
cat >"$t/bin/gh" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$GH_LOG"
if [ "$1 $2" = "issue list" ]; then printf '%s' "${GH_OPEN:-}"; fi
STUB
chmod +x "$t/bin/curl" "$t/bin/gh"
export PATH="$t/bin:$PATH" SITE="$t/site" GH_LOG="$t/gh.log"

publish() {
  rm -rf "$t/site"/*
  for f in "$root"/spec/*.wsdl "$root"/spec/*.xsd "$root"/spec/ca/* "$root"/spec/requests/*; do cp "$f" "$t/site/"; done
  (cd "$root/spec" && zip -q "$t/site/caeetapi_jwt.zip" caeetapi_jwt.yml)
}

publish
out=$("$script" run); rc=$?
exits "the same files as spec/ pass" 0
says "and say so" "matches"

publish
echo '<!-- edited -->' >>"$t/site/EETXMLSchema.xsd"
out=$("$script" run); rc=$?
exits "a changed schema fails the run" 1
says "and the report names it" "changed.*spec/EETXMLSchema.xsd"
omits "and no file that did not change" "spec/EETServiceSOAP.wsdl"

publish
rm "$t/site/CZ00000019.eet.v4.req.xml"
out=$("$script" run); rc=$?
exits "a file that no longer downloads fails the run, never reads as no change" 1
says "and is reported as gone" "gone.*spec/requests/CZ00000019.eet.v4.req.xml"

publish
mkdir -p "$t/zip" && cp "$root/spec/caeetapi_jwt.yml" "$t/zip/" && echo "# edited" >>"$t/zip/caeetapi_jwt.yml"
rm "$t/site/caeetapi_jwt.zip" && (cd "$t/zip" && zip -q "$t/site/caeetapi_jwt.zip" caeetapi_jwt.yml)
out=$("$script" run); rc=$?
exits "a change inside the renewal API's zip fails the run" 1
says "and is reported" "changed.*spec/caeetapi_jwt.yml"

echo "body" >"$t/body.md"
: >"$GH_LOG"
GH_OPEN="" "$script" report "$t/body.md"
logged "the first report opens an issue" "^issue create"
: >"$GH_LOG"
GH_OPEN="12" "$script" report "$t/body.md"
logged "a later one comments on the open issue" "^issue comment 12"
[ "$(grep -c "^issue create" "$GH_LOG")" -eq 0 ]
verdict "and opens no second one" $?

exit $((fails > 0))
