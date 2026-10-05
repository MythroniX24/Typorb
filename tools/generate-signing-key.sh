#!/usr/bin/env sh
# Generates the release signing key Typorb's CI expects, and the four repository secrets that go
# with it.
#
#   sh tools/generate-signing-key.sh [target-dir]
#
# Why this exists
# ---------------
# Without these secrets the workflow invents a throwaway key per run (see the header of
# .github/workflows/build-release-apk.yml), which has three visible costs:
#
#   1. Android refuses to upgrade over an app signed with a different key, so every release starts
#      with "uninstall the old one first";
#   2. Play Protect sees a developer it has never met on every single build, which is a large part
#      of why sideloading Typorb raises "unrecognized app" rather than installing quietly;
#   3. Android developer verification (protections from 30 September 2026 on certified devices)
#      registers a package name *together with its signing key* — a key that changes every release
#      cannot be registered at all.
#
# Keep the output safe: losing the key only costs you in-place upgrades (a fresh install still
# works), it does not cost the app. Do not commit it — .gitignore already ignores *.jks.
set -eu

TARGET_DIR="${1:-.}"
KEYSTORE="$TARGET_DIR/typorb-release.jks"
SECRETS_FILE="$TARGET_DIR/typorb-signing-secrets.txt"
KEY_ALIAS="typorb"

if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool not found. Install a JDK 17 (or newer) and run this again." >&2
    exit 1
fi

if [ -f "$KEYSTORE" ]; then
    echo "error: $KEYSTORE already exists — refusing to overwrite a signing key." >&2
    echo "       Move it aside first if you really mean to replace it." >&2
    exit 1
fi

# ~32 characters of [A-Za-z0-9]. This is a build secret, not something to memorise: it is written
# next to the keystore so nothing depends on remembering it.
PASSWORD="$(head -c 96 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"

keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Typorb, OU=Release, O=Typorb, L=-, ST=-, C=ZZ" > /dev/null

# One unwrapped line: the workflow decodes this with `base64 -d`, and a single-line secret is far
# easier to paste into the GitHub UI without losing a character.
if command -v openssl >/dev/null 2>&1; then
    BASE64="$(openssl base64 -A -in "$KEYSTORE")"
else
    BASE64="$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"
fi

umask 077
cat > "$SECRETS_FILE" <<EOF
TYPORB_KEYSTORE_BASE64=$BASE64
TYPORB_STORE_PASSWORD=$PASSWORD
TYPORB_KEY_ALIAS=$KEY_ALIAS
TYPORB_KEY_PASSWORD=$PASSWORD
EOF

cat <<EOF
Signing key written:  $KEYSTORE
Secrets written:      $SECRETS_FILE  (readable by you only — back both files up)

Add the four values as repository secrets, either in
Settings -> Secrets and variables -> Actions -> New repository secret,
or with the GitHub CLI from the same directory:

  while IFS='=' read -r name value; do gh secret set "\$name" --body "\$value"; done < "$SECRETS_FILE"

  (needs the GitHub CLI, logged in to the account that owns the repository)

Secret names, exactly as the workflow reads them:

  TYPORB_KEYSTORE_BASE64   the single-line base64 in $SECRETS_FILE
  TYPORB_STORE_PASSWORD    in $SECRETS_FILE
  TYPORB_KEY_ALIAS         $KEY_ALIAS
  TYPORB_KEY_PASSWORD      same value as the store password

After that, the next release is signed with this key: it installs over the previous build instead
of demanding an uninstall, and Play Protect sees one developer instead of a new one every build.
EOF
