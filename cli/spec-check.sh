#!/usr/bin/env bash
# Compares spec/ with what eet.gov.cz publishes today, so a new WSDL, schema or renewal API is noticed rather than
# found by a failing customer. The files have no version, only content, so nothing like Dependabot can watch them.
#
#   spec-check.sh run              fetch every source, compare, print a Markdown report; exit 1 when anything moved
#   spec-check.sh run --report     the same, and open or comment on the GitHub issue when it did (needs gh, GH_TOKEN)
#   spec-check.sh fetch <dir>      download every source into <dir>, by its path under spec/
#   spec-check.sh compare <dir>    compare <dir> with spec/; exit 1 on a difference or a file missing from <dir>
#   spec-check.sh report <file>    open the issue with <file> as its body, or comment on the one already open
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
base="https://eet.gov.cz/assets/cs/cmsmedia/pro-vyvojare"
title="spec: the tax office changed a file in spec/"

# <path under spec/> <file on eet.gov.cz> [<member, when that file is a zip>]
sources() {
  cat <<'LIST'
EETServiceSOAP.wsdl EETServiceSOAP.wsdl
EETXMLSchema.xsd EETXMLSchema.xsd
caeetapi_jwt.yml caeetapi_jwt.zip caeetapi_jwt.yml
ca/ca_eet-root_cert-playground.crt ca_eet-root_cert-playground.crt
ca/ca_eet-root_prod.pem ca_eet-root_prod.pem
ca/ca_eet-sub_cert-playground.crt ca_eet-sub_cert-playground.crt
ca/ca_eet-sub_prod.pem ca_eet-sub_prod.pem
requests/CZ00000019.eet.v4.req.xml CZ00000019.eet.v4.req.xml
requests/CZ683555118.eet.v4.req.xml CZ683555118.eet.v4.req.xml
requests/CZ8551015704.eet.v4.req.xml CZ8551015704.eet.v4.req.xml
LIST
}

# A source that does not download leaves no file, which compare reports as gone: an error is never "no change".
fetch() {
  local dir=$1 path name member out
  while read -r path name member; do
    out="$dir/$path"
    mkdir -p "$(dirname "$out")"
    if [ -n "$member" ]; then
      if ! { curl -fsSL -o "$dir/.zip" "$base/$name" && unzip -p "$dir/.zip" "$member" >"$out"; }; then rm -f "$out"; fi
      rm -f "$dir/.zip"
    else
      curl -fsSL -o "$out" "$base/$name" || rm -f "$out"
    fi
  done < <(sources)
}

compare() {
  local dir=$1 path name member rc=0
  while read -r path name member; do
    if [ ! -s "$dir/$path" ]; then
      printf -- "- **gone**: \`spec/%s\`, %s/%s did not download\n" "$path" "$base" "$name"
      rc=1
    elif ! cmp -s "$root/spec/$path" "$dir/$path"; then
      printf -- "- **changed**: \`spec/%s\`, from %s/%s\n\n\`\`\`diff\n" "$path" "$base" "$name"
      diff -u "$root/spec/$path" "$dir/$path" | head -n 200 || true
      printf '```\n\n'
      rc=1
    fi
  done < <(sources)
  return $rc
}

run() {
  t=$(mktemp -d)
  trap 'rm -rf "$t"' EXIT
  fetch "$t"
  if compare "$t" >"$t/report.md"; then
    echo "spec/ matches eet.gov.cz"
    return 0
  fi
  {
    echo "The weekly check found spec/ no longer matches what eet.gov.cz publishes. The tests read spec/, so a"
    echo "changed file means a code change: update the file byte for byte, fix what its tests then say, and say"
    echo "in spec/README.md what changed. A file that is gone may have moved; find it on"
    echo "https://eet.gov.cz/cs/pro-vyvojare/dokumenty-ke-stazeni?category=provyvojare and fix its URL in cli/spec-check.sh."
    echo
    cat "$t/report.md"
  } >"$t/body.md"
  cat "$t/body.md"
  if [ "${1:-}" = "--report" ]; then report "$t/body.md"; fi
  return 1
}

# One open issue at a time: a second week of the same change comments instead of opening another.
report() {
  local body=$1 open
  open=$(gh issue list --state open --search "in:title \"$title\"" --json number,title \
    --jq ".[] | select(.title == \"$title\") | .number" | head -n 1)
  if [ -n "$open" ]; then
    gh issue comment "$open" --body-file "$body"
  else
    gh issue create --title "$title" --body-file "$body"
  fi
}

case "${1:-}" in
  run) shift; run "$@" ;;
  fetch) fetch "$2" ;;
  compare) compare "$2" ;;
  report) report "$2" ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
