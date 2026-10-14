#!/bin/bash
# Chop Chop Flashlight — installer.
#
# Sets up local Android signing config for this project:
#   * If a release keystore already exists, this verifies the passwords
#     against it and saves them to a local 0600 config file. The keystore
#     is NEVER regenerated, overwritten, or deleted by this script — the
#     same key must sign every update of the published app.
#   * If no keystore exists, this generates one with keytool and saves
#     the (prompted or randomly generated) passwords to the same config.
#
# The config file lives OUTSIDE the repo (default:
# ~/.config/chop-chop-flashlight/config.env) and must never be committed.
# The build scripts (build-manual.sh, build-bundle.sh) read it at build
# time. No password is ever passed on the command line or printed.
#
# Usage:
#   ./install.sh [--non-interactive] [--config-file PATH]
#                [--keystore PATH] [--alias ALIAS]
#
# Interactive mode prompts for any missing value (passwords via read -s;
# an empty store password means "generate a random one" when creating a
# new keystore). A separate key password is only prompted for non-PKCS12
# (existing) keystores — see the NOTE above.
# Non-interactive mode reads values from the environment (see below) and
# generates random passwords for anything not supplied. There are no CLI
# flags for passwords on purpose: flags end up in shell history.
#
# Environment variables (also honoured in interactive mode):
#   CHOPCHOP_CONFIG_FILE          config file path
#   CHOPCHOP_KEYSTORE_PATH       keystore path
#   CHOPCHOP_KEYSTORE_ALIAS      key alias
#   CHOPCHOP_KEYSTORE_PASSWORD   keystore (store) password
#   CHOPCHOP_KEY_PASSWORD        key password (PKCS12 keystores: ignored
#                                unless equal to the store password; only
#                                non-PKCS12 keystores use a distinct one)
#   CHOPCHOP_NON_INTERACTIVE=1   same as --non-interactive
#
# NOTE: keytool creates PKCS12 keystores, which do not support a key
# password different from the store password (the key is protected with
# the store password). install.sh therefore always saves the key password
# equal to the store password for PKCS12 keystores; a supplied
# CHOPCHOP_KEY_PASSWORD that differs is ignored, with a note.
# Only a non-PKCS12 (e.g. JKS) keystore can have a distinct key password.
#
# Secrets are never printed. This script deliberately never enables xtrace.
set -euo pipefail
umask 077

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NON_INTERACTIVE=0
CLI_CONFIG_FILE=""
CLI_KEYSTORE=""
CLI_ALIAS=""

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  echo "Options:"
  echo "  --non-interactive    read values from the environment, no prompts"
  echo "  --config-file PATH   config file to read/write"
  echo "  --keystore PATH      keystore path to use or create"
  echo "  --alias ALIAS        key alias to use"
  echo "  --help               show this help"
}

die() {
  echo "install.sh: $*" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1 ;;
    --config-file) [ $# -ge 2 ] || die "--config-file needs a path"; CLI_CONFIG_FILE="$2"; shift ;;
    --keystore) [ $# -ge 2 ] || die "--keystore needs a path"; CLI_KEYSTORE="$2"; shift ;;
    --alias) [ $# -ge 2 ] || die "--alias needs a value"; CLI_ALIAS="$2"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
  shift
done

if [ -n "${CHOPCHOP_NON_INTERACTIVE:-}" ] && [ "${CHOPCHOP_NON_INTERACTIVE}" != "0" ]; then
  NON_INTERACTIVE=1
fi

# Stash environment-provided values before loading the existing config
# file, so the environment wins over stored values.
ENV_KEYSTORE_PATH="${CHOPCHOP_KEYSTORE_PATH:-}"
ENV_ALIAS="${CHOPCHOP_KEYSTORE_ALIAS:-}"
ENV_STOREPASS="${CHOPCHOP_KEYSTORE_PASSWORD:-}"
ENV_KEYPASS="${CHOPCHOP_KEY_PASSWORD:-}"

CONFIG_FILE="${CLI_CONFIG_FILE:-${CHOPCHOP_CONFIG_FILE:-$HOME/.config/chop-chop-flashlight/config.env}}"

# Load existing config (if any) for defaults on re-install.
if [ -f "$CONFIG_FILE" ]; then
  # The config file contains only VAR='value' assignments written by this
  # script; source it so re-installs reuse the stored values.
  # shellcheck disable=SC1090
  . "$CONFIG_FILE"
fi

DEFAULT_KEYSTORE="$PROJECT_DIR/manual/release.keystore"
KEYSTORE="${CLI_KEYSTORE:-${ENV_KEYSTORE_PATH:-${CHOPCHOP_KEYSTORE_PATH:-$DEFAULT_KEYSTORE}}}"
ALIAS="${CLI_ALIAS:-${ENV_ALIAS:-${CHOPCHOP_KEYSTORE_ALIAS:-}}}"
STOREPASS="${ENV_STOREPASS:-${CHOPCHOP_KEYSTORE_PASSWORD:-}}"
KEYPASS="${ENV_KEYPASS:-${CHOPCHOP_KEY_PASSWORD:-}}"
# Unset the sourced plain names after resolving, so nothing below can
# accidentally treat a stale value as authoritative.
unset CHOPCHOP_KEYSTORE_PATH CHOPCHOP_KEYSTORE_ALIAS
unset CHOPCHOP_KEYSTORE_PASSWORD CHOPCHOP_KEY_PASSWORD

find_keytool() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    printf '%s\n' "$JAVA_HOME/bin/keytool"
    return 0
  fi
  for cand in "$HOME/workspace/tooling/jdk" \
              "$HOME/workspace/tooling/jdk/jdk-17.0.20.1+1" \
              /usr/lib/jvm/java-17-openjdk-amd64; do
    if [ -x "$cand/bin/keytool" ]; then
      printf '%s\n' "$cand/bin/keytool"
      return 0
    fi
  done
  if command -v keytool >/dev/null 2>&1; then
    command -v keytool
    return 0
  fi
  return 1
}

KEYTOOL="$(find_keytool)" || die "keytool not found. Install JDK 17 or set JAVA_HOME to a JDK."

# Random strong password: base64 from openssl, or alphanumerics from
# /dev/urandom as a fallback. Output goes to stdout for capture only.
gen_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24
  else
    tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32
    printf '\n'
  fi
}

# Shell-quote one value for safe round-tripping through the config file.
shell_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# Prompt for a value with a default (stdout = answer, prompts -> stderr).
prompt_value() {
  local label="$1" default="$2" answer=""
  if [ -n "$default" ]; then
    read -r -p "$label [$default]: " answer >&2
    printf '%s\n' "${answer:-$default}"
  else
    read -r -p "$label: " answer >&2
    printf '%s\n' "$answer"
  fi
}

# Prompt for a password (stdout = password, prompts -> stderr).
# $1 = label, $2 = mode: "existing" (must be typed, never generated),
# "new-store" (empty = generate random), "new-key" (empty = same as store).
# Typed passwords are confirmed by re-typing.
prompt_password() {
  local label="$1" mode="$2" first="" second="" attempt=0
  while [ "$attempt" -lt 3 ]; do
    attempt=$((attempt + 1))
    read -r -s -p "$label: " first >&2
    echo >&2
    if [ -z "$first" ]; then
      case "$mode" in
        existing) echo "Password must not be empty for an existing keystore." >&2; continue ;;
        new-store) printf '%s\n' "$(gen_password)"; return 0 ;;
        new-key) printf '%s\n' ""; return 0 ;;  # caller maps empty -> store password
      esac
    fi
    if [ "${#first}" -lt 6 ]; then
      echo "keytool requires at least 6 characters." >&2
      continue
    fi
    read -r -s -p "Confirm $label: " second >&2
    echo >&2
    if [ "$first" = "$second" ]; then
      printf '%s\n' "$first"
      return 0
    fi
    echo "Passwords did not match, try again." >&2
  done
  die "too many failed password attempts"
}

# keytool -list validates the store password (and alias) without changing
# anything. Output is discarded; only the exit code is used.
verify_keystore() {
  "$KEYTOOL" -list -keystore "$KEYSTORE" -storepass "$1" -alias "$ALIAS" >/dev/null 2>&1
}

if [ -f "$KEYSTORE" ]; then
  # Existing keystore: NEVER regenerate. Verify the given passwords
  # against it, then just (re)save the config.
  echo "Keystore already exists at $KEYSTORE — it will not be regenerated."
  if [ -z "$ALIAS" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
      die "CHOPCHOP_KEYSTORE_ALIAS is required when a keystore already exists"
    fi
    ALIAS="$(prompt_value "Key alias" "chopchop")"
  fi
  if [ -z "$STOREPASS" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
      die "CHOPCHOP_KEYSTORE_PASSWORD is required when a keystore already exists"
    fi
    STOREPASS="$(prompt_password "Keystore password" "existing")"
  fi
  attempt=0
  while ! verify_keystore "$STOREPASS"; do
    attempt=$((attempt + 1))
    [ "$attempt" -ge 3 ] && die "could not verify keystore password/alias (wrong password, or wrong alias for this keystore)"
    [ "$NON_INTERACTIVE" -eq 1 ] && die "could not verify keystore password/alias (wrong password, or wrong alias for this keystore)"
    echo "That password/alias did not unlock the keystore, try again." >&2
    STOREPASS="$(prompt_password "Keystore password" "existing")"
  done
  echo "Keystore password verified."
  # Resolve the key password. PKCS12 keystores (keytool's default) do
  # not support a key password different from the store password, so
  # enforce equality — otherwise the build scripts would fail at sign
  # time with a key password the keystore cannot have. Only a
  # non-PKCS12 (e.g. JKS) keystore can have a distinct key password.
  if "$KEYTOOL" -list -keystore "$KEYSTORE" -storepass "$STOREPASS" 2>/dev/null | grep -qi 'Keystore type: PKCS12'; then
    if [ -n "$KEYPASS" ] && [ "$KEYPASS" != "$STOREPASS" ]; then
      echo "Note: this is a PKCS12 keystore, which does not support a key password different from the store password — using the store password as the key password." >&2
    fi
    KEYPASS="$STOREPASS"
  elif [ -z "$KEYPASS" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
      KEYPASS="$STOREPASS"
    else
      KEYPASS="$(prompt_password "Key password (empty = same as keystore password)" "new-key")"
      [ -z "$KEYPASS" ] && KEYPASS="$STOREPASS"
    fi
  fi
  KEYSTORE_CREATED=0
else
  # No keystore: create one. Alias/passwords come from env/config, from
  # prompts, or are generated randomly (non-interactive without env).
  if [ -z "$ALIAS" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
      ALIAS="chopchop"
    else
        ALIAS="$(prompt_value "Key alias" "chopchop")"
    fi
  fi
  case "$ALIAS" in
    *\ *|*\"*|*\'*) die "alias must not contain spaces or quotes" ;;
  esac
  if [ -z "$STOREPASS" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
      STOREPASS="$(gen_password)"
    else
        STOREPASS="$(prompt_password "New keystore password (empty = generate a random one)" "new-store")"
    fi
  fi
  # PKCS12 keystores (keytool's default) do not support a key password
  # different from the store password, so a new keystore's key password
  # is the store password. A supplied CHOPCHOP_KEY_PASSWORD that differs
  # is ignored so the saved config can never fail at sign time.
  if [ -n "$KEYPASS" ] && [ "$KEYPASS" != "$STOREPASS" ]; then
    echo "Note: PKCS12 keystores do not support a key password different from the store password — ignoring CHOPCHOP_KEY_PASSWORD and using the store password as the key password." >&2
  fi
  KEYPASS="$STOREPASS"
  echo "Generating new keystore at $KEYSTORE (alias: $ALIAS)..."
  mkdir -p "$(dirname "$KEYSTORE")"
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass "$STOREPASS" -keypass "$KEYPASS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Chop Chop Flashlight,C=US" >/dev/null 2>&1 \
    || die "keytool failed to generate the keystore"
  verify_keystore "$STOREPASS" || die "generated keystore failed verification"
  KEYSTORE_CREATED=1
fi

# Write the config atomically, 0600, without ever printing a secret.
CONFIG_DIR="$(dirname "$CONFIG_FILE")"
mkdir -p "$CONFIG_DIR"
TMP_CONFIG="$CONFIG_DIR/.config.env.tmp.$$"
{
  echo "# Chop Chop Flashlight — local signing config."
  echo "# Written by install.sh. NEVER commit this file: it holds signing"
  echo "# passwords. The build scripts read it at build time."
  echo "CHOPCHOP_KEYSTORE_PATH=$(shell_quote "$KEYSTORE")"
  echo "CHOPCHOP_KEYSTORE_ALIAS=$(shell_quote "$ALIAS")"
  echo "CHOPCHOP_KEYSTORE_PASSWORD=$(shell_quote "$STOREPASS")"
  echo "CHOPCHOP_KEY_PASSWORD=$(shell_quote "$KEYPASS")"
} > "$TMP_CONFIG"
chmod 0600 "$TMP_CONFIG"
mv -f "$TMP_CONFIG" "$CONFIG_FILE"
chmod 700 "$CONFIG_DIR" 2>/dev/null || true

# Sanity: the keystore must be git-ignored wherever it lives in the repo.
if [ -f "$PROJECT_DIR/.gitignore" ]; then
  if ! grep -q '^\*\.keystore' "$PROJECT_DIR/.gitignore"; then
    echo "WARNING: $PROJECT_DIR/.gitignore does not ignore *.keystore — add it before committing." >&2
  fi
else
  echo "WARNING: no .gitignore in $PROJECT_DIR — keystore files must be ignored before committing." >&2
fi

echo "Done."
echo "  Config:   $CONFIG_FILE (0600)"
echo "  Keystore: $KEYSTORE (alias: $ALIAS)"
if [ "$KEYSTORE_CREATED" -eq 1 ]; then
  echo "  Keystore: newly generated"
else
  echo "  Keystore: existing, unchanged"
fi
echo "Next: ./build-manual.sh  (APK) or ./build-bundle.sh  (Play AAB)"
