#!/usr/bin/env bash
#
# check-debug-signing.sh — guard for the pinned debug keystore.
#
# CI runners used to generate a random debug key per machine, so every dev
# build had a different signature and could not install over the previous one
# ("signatures do not match"). The fix pins a committed debug-only keystore
# (app/debug.keystore, storepass "android", alias "androiddebugkey").
# This guard verifies the keystore is committed, stored binary, and actually
# referenced by the debug signingConfig. The cryptographic open test runs only
# when a JDK (keytool) is available.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

KEYSTORE="app/debug.keystore"
fail() { echo "FAIL: $1" >&2; exit 1; }

[[ -f "$KEYSTORE" ]] || fail "$KEYSTORE is missing — CI would fall back to a random per-runner debug key"
git ls-files --error-unmatch "$KEYSTORE" > /dev/null 2>&1 || fail "$KEYSTORE is not tracked by git"

attr="$(git check-attr text -- "$KEYSTORE" | awk '{print $3}')"
[[ "$attr" == "unset" ]] || fail "$KEYSTORE is not marked binary in .gitattributes (text=$attr)"

grep -q 'storeFile = file("debug.keystore")' app/build.gradle.kts \
    || fail "app/build.gradle.kts debug signingConfig no longer points at the committed debug.keystore"
grep -q 'keyAlias = "androiddebugkey"' app/build.gradle.kts \
    || fail "app/build.gradle.kts debug signingConfig no longer uses the androiddebugkey alias"

if command -v keytool > /dev/null 2>&1; then
    keytool -list -keystore "$KEYSTORE" -storepass android -alias androiddebugkey > /dev/null 2>&1 \
        || fail "$KEYSTORE does not open with storepass 'android' / alias 'androiddebugkey'"
    echo "OK: debug keystore is committed, binary-clean, pinned in signingConfig, and opens with the expected alias"
else
    echo "WARN: keytool not found — skipped cryptographic open test"
    echo "OK: debug keystore is committed, binary-clean, and pinned in signingConfig"
fi
