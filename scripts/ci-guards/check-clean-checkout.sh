#!/usr/bin/env bash
#
# check-clean-checkout.sh — fail fast on cache-masked dependency breakage.
#
# Creates a fresh worktree of this repo at HEAD and resolves the app's runtime
# classpath with a cold Gradle cache. Catches artifacts that only resolve
# because of a warm dependency cache — e.g. the JitPack 404 for
# arkon/FlexibleAdapter@c8013533, which broke CI on fresh runners while local
# builds stayed green behind the cache.
#
# Dependency *resolution* (not a full build) is the check: the failure mode was
# at resolution time, and resolution takes minutes instead of the ~12 min build.
#
# Requires a JVM and an Android SDK (ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in
# local.properties) because AGP needs the SDK to configure the app module. Without
# them the check is skipped with a warning.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIGURATION="${1:-debugRuntimeClasspath}"

can_run_gradle() {
    # Need both a JVM and the Android SDK: Gradle needs a JVM to run at all,
    # and AGP needs the SDK to configure the app module.
    if ! command -v java > /dev/null 2>&1 && [[ -z "${JAVA_HOME:-}" ]]; then
        return 1
    fi
    [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]] && return 0
    [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]] && return 0
    [[ -f "$REPO_ROOT/local.properties" ]] && grep -q '^sdk\.dir=' "$REPO_ROOT/local.properties" && return 0
    return 1
}

if ! can_run_gradle; then
    echo "WARN: no JVM + Android SDK found (need java/JAVA_HOME plus ANDROID_HOME / ANDROID_SDK_ROOT / sdk.dir in local.properties)."
    echo "      Skipping clean-checkout dependency check."
    exit 0
fi

WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/komikku-clean-check.XXXXXX")"
cleanup() {
    git -C "$REPO_ROOT" worktree remove --force "$WORKDIR/repo" >/dev/null 2>&1 || true
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "==> clean-checkout: fresh worktree at $WORKDIR/repo, cold Gradle cache"
git -C "$REPO_ROOT" worktree add --detach "$WORKDIR/repo" HEAD >/dev/null

(
    cd "$WORKDIR/repo"
    export GRADLE_USER_HOME="$WORKDIR/gradle-home"
    ./gradlew :app:dependencies --configuration "$CONFIGURATION" --no-daemon -q > /dev/null
)

echo "OK: dependencies resolve from a clean checkout with a cold cache ($CONFIGURATION)"
