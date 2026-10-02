#!/usr/bin/env bash
#
# check-vendored-aar.sh — integrity guard for the vendored FlexibleAdapter AAR.
#
# The AAR under maven-repo/ was rebuilt from pinned sources because JitPack can
# no longer build arkon/FlexibleAdapter@c8013533. Two things broke around it:
#   1. `.gitattributes` had `* text eol=lf`, which corrupted the AAR on commit
#      (124531 vs 124533 bytes, broken ZIP) until `*.aar binary` was added.
#   2. A missing/corrupt AAR only surfaces as a dependency-resolution failure
#      deep into the CI build.
# This guard verifies the file exists, is tracked, is marked binary, is a valid
# ZIP, and matches the recorded SHA-256 — all in under a second, before push.
#
# When the vendored AAR is intentionally replaced, update EXPECTED_SHA256 below
# (use: sha256sum <file>  or  shasum -a 256 <file>).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

AAR="maven-repo/com/github/arkon/FlexibleAdapter/flexible-adapter/c8013533/flexible-adapter-c8013533.aar"
# Recorded when the AAR was vendored (PR #4). Update when the AAR is replaced.
EXPECTED_SHA256="f1014cc6157b5f7a0033094b646cacb547622911434395f88174d0bf4fe7ae47"

fail() { echo "FAIL: $1" >&2; exit 1; }

[[ -f "$AAR" ]] || fail "$AAR is missing"
git ls-files --error-unmatch "$AAR" > /dev/null 2>&1 || fail "$AAR is not tracked by git"

# Must be stored binary — text/line-ending conversion corrupts the ZIP.
attr="$(git check-attr text -- "$AAR" | awk '{print $3}')"
[[ "$attr" == "unset" ]] || fail "$AAR is not marked binary in .gitattributes (text=$attr); line-ending conversion would corrupt it"

# Valid ZIP archive.
python3 -c "import sys,zipfile; sys.exit(1 if zipfile.ZipFile(sys.argv[1]).testzip() else 0)" "$AAR" \
    || fail "$AAR is not a valid ZIP archive"

if command -v sha256sum > /dev/null 2>&1; then
    actual="$(sha256sum "$AAR" | awk '{print $1}')"
elif command -v shasum > /dev/null 2>&1; then
    actual="$(shasum -a 256 "$AAR" | awk '{print $1}')"
else
    actual="$(python3 -c "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$AAR")"
fi
[[ "$actual" == "$EXPECTED_SHA256" ]] || fail "$AAR checksum mismatch: got $actual, expected $EXPECTED_SHA256"

# The vendored group must still resolve exclusively from maven-repo/, never JitPack.
grep -q 'includeGroup("com.github.arkon.FlexibleAdapter")' settings.gradle.kts \
    || fail "settings.gradle.kts no longer pins com.github.arkon.FlexibleAdapter to the vendored maven-repo"

echo "OK: vendored AAR is present, binary-clean, a valid ZIP, and checksum-verified"
