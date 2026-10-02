#!/usr/bin/env bash
#
# pre-push-checks.sh — run all fail-fast CI guards before pushing.
#
# Catches the classes of breakage that previously only surfaced mid-CI:
#   1. clean-checkout  — dependency resolution from a fresh worktree with a cold
#                        Gradle cache (catches cache-masked failures like the
#                        JitPack 404 for FlexibleAdapter).
#   2. vendored-aar    — integrity of the vendored AAR (catches .gitattributes
#                        line-ending corruption and checksum drift).
#   3. debug-signing   — pinned debug keystore (catches signature-mismatch
#                        regressions where CI mints a random debug key).
#
# Usage: ./scripts/pre-push-checks.sh
# Exits non-zero on the first failing guard.

set -euo pipefail

GUARDS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/ci-guards" && pwd)"

guards=(
    "check-clean-checkout.sh"
    "check-vendored-aar.sh"
    "check-debug-signing.sh"
)

for guard in "${guards[@]}"; do
    echo "--- $guard ---"
    "$GUARDS_DIR/$guard"
    echo
done

echo "All pre-push guards passed."
