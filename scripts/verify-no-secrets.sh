#!/usr/bin/env bash
set -euo pipefail

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "Debug APK not found: $APK_PATH" >&2
  exit 1
fi

TRACKED_PRIVATE_FILES="$(git ls-files | grep -E '(^|/)(\.env|local\.properties|[^/]*\.(jks|keystore|p12|pfx))$' || true)"
if [[ -n "$TRACKED_PRIVATE_FILES" ]]; then
  echo "Private configuration or signing material is tracked by Git:" >&2
  echo "$TRACKED_PRIVATE_FILES" >&2
  exit 1
fi

PATTERN='-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|client_secret[[:space:]]*[:=][[:space:]]*["\x27]?[A-Za-z0-9_./+=-]{12,}|(STORE_PASSWORD|KEY_PASSWORD|FIREBASE_APPCHECK_DEBUG_TOKEN)[[:space:]]*=[[:space:]]*[^[:space:]]{8,}'

if grep -R -a -E --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=build --exclude='*.example' "$PATTERN" .; then
  echo "Potential private credential found in repository source." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
unzip -q "$APK_PATH" -d "$TMP_DIR"
if grep -R -a -E "$PATTERN" "$TMP_DIR"; then
  echo "Potential private credential found inside the debug APK." >&2
  exit 1
fi

echo "No tracked private signing files or matching private credentials found in source or debug APK."
