# Chop Chop Flashlight

An Android utility that toggles your phone's flashlight with a double
chop-chop motion: two quick chops in the air, like a karate chop. No
accounts, no ads, no analytics, no internet access — detection happens
entirely on-device.

## What it does

- **Chop-chop gesture:** two sharp chops close together toggle the
  flashlight on or off.
- **Works with the screen off:** a lightweight foreground service keeps
  listening, with a minimal persistent notification.
- **Personal calibration:** the app learns your chop in about 10 seconds
  (about 10 chop-chop pairs), so it responds to you and ignores
  everything else. Recalibrate anytime from the main screen.
- **Haptic confirmation:** an optional buzz each time the light toggles.
- **Respects your battery:** only the accelerometer is monitored, and all
  processing is a small amount of arithmetic on each sensor sample.

## How detection works

The detector watches the phone's accelerometer for two sharp peaks in
gravity-free linear acceleration close together, separated by a dip (so
one long shake does not count twice), followed by a short cooldown.
Calibration fits the peak/valley thresholds, timing window, and chop
direction to the person holding the phone; samples outside a cone
around the learned chop direction never count as peaks. Defaults ship
in the app; calibration overrides them and stores the result in the
app's private on-device storage.

## Requirements

- JDK 17 (`javac`, `keytool`, `jarsigner` on `PATH`, or `JAVA_HOME` set)
- Android SDK with:
  - `build-tools/36.1.0` (`aapt2`, `d8`, `zipalign`, `apksigner`)
  - `platforms/android-36/android.jar`
- Python 3 (one packaging step in the APK build)
- `bundletool.jar` (only for the Play `.aab` bundle build)
- `adb` (only for installing the APK on a device)
- `openssl` (only for random password generation in `install.sh`;
  falls back to `/dev/urandom`)

No Gradle, no downloaded dependencies: the builds use only the local
SDK tools listed above.

## Install (signing setup)

The app is signed with a release keystore. `install.sh` sets that up;
it never commits anything and never prints a password.

```sh
./install.sh
```

Interactive mode prompts for any missing value (passwords via `read -s`):

- **Key alias** (default `chopchop`)
- **Keystore password** — empty means "generate a random one" when
  creating a new keystore

No separate key-password prompt: `keytool` creates PKCS12 keystores,
which do not support a key password different from the store password,
so the key password is always the store password. (Only a non-PKCS12
keystore, e.g. JKS, can have a distinct key password; supply it via
`CHOPCHOP_KEY_PASSWORD` when registering such an existing keystore.)

Non-interactive mode reads values from the environment instead of
prompting, and generates random passwords for anything not supplied:

```sh
CHOPCHOP_KEYSTORE_PASSWORD="..." CHOPCHOP_KEY_PASSWORD="..." \
  ./install.sh --non-interactive
```

| Environment variable | Meaning |
| --- | --- |
| `CHOPCHOP_CONFIG_FILE` | Config file path (default `~/.config/chop-chop-flashlight/config.env`) |
| `CHOPCHOP_KEYSTORE_PATH` | Keystore path (default `<repo>/manual/release.keystore`) |
| `CHOPCHOP_KEYSTORE_ALIAS` | Key alias (default `chopchop`) |
| `CHOPCHOP_KEYSTORE_PASSWORD` | Keystore (store) password |
| `CHOPCHOP_KEY_PASSWORD` | Key password (PKCS12 keystores: ignored unless equal to the store password; only non-PKCS12 keystores use a distinct one) |
| `CHOPCHOP_NON_INTERACTIVE=1` | Same as `--non-interactive` |

### What config is collected, and where it goes

`install.sh` writes exactly four values to the config file:

- keystore path
- key alias
- keystore (store) password
- key password

Default location: `~/.config/chop-chop-flashlight/config.env`,
permissions `0600`. The config lives **outside** the repo, and
`.gitignore` also excludes `*.keystore`, `*.env`, and the other
signing-config filenames in case one ever lands in the tree. There are
deliberately no CLI flags for passwords — flags end up in shell
history.

### Migrating an existing keystore

If a keystore already exists at the configured path, `install.sh`
**never regenerates it** — the same key must sign every update of the
published app. It verifies the entered password against the existing
keystore and saves the config. Run `./install.sh`, enter the existing
keystore password when prompted, and the build scripts will pick it up.

### Rotating passwords

Changing a keystore/key password does not change the key itself, so
published-app updates keep working. To rotate, use `keytool`
(`-storepasswd` / `-keypasswd`), then re-run `./install.sh` with the
new passwords so the config file matches.

## Build

Run `install.sh` first — the build scripts read the signing config
from it and stop with a pointer back to `install.sh` if it is missing.

```sh
./build-manual.sh    # -> ChopChopFlashlight.apk (signed)
./build-bundle.sh    # -> ChopChopFlashlight.aab (signed, for Play)
```

Install the APK on a connected device with:

```sh
adb install -r ChopChopFlashlight.apk
```

## Tests

Headless detector/tuner tests (no Android device needed):

```sh
./run-tests.sh
```

## Project layout

| Path | Contents |
| --- | --- |
| `install.sh` | Signing setup (this document's Install section) |
| `build-manual.sh` | Manual APK build: `aapt2` + `javac` + `d8` + `zipalign` + `apksigner` |
| `build-bundle.sh` | Play bundle build: `aapt2` + `bundletool` + `jarsigner` |
| `run-tests.sh` | Headless detector/tuner tests |
| `manual/` | App source: `AndroidManifest.xml`, `java/`, `res/` |
| `manual/release.keystore` | Release signing key — local only, never committed |
| `app-icon-512.png`, `feature-graphic.png` | Play Store graphics |
| `play-screenshot-*.jpg` | Play Store screenshots |
| `play-listing.md` | Play Store listing kit (descriptions, declarations, checklist) |
| `privacy-policy.html` | Privacy policy |

## Privacy

The app collects nothing and sends nothing anywhere. It does not
request internet access, so it has no technical means of transmitting
data off the device. Motion sensor data is processed in memory the
moment it is read, never recorded or stored. Preferences and
calibration thresholds live only in the app's private on-device
storage. See `privacy-policy.html` for the full policy. Before
publishing, set the contact address in that file to the address you
want to be public, and host the policy at a public URL for the store
listing.

## Publishing

`play-listing.md` is the Play Store listing kit: store descriptions,
data-safety / ads / foreground-service declarations, required assets,
and the release checklist. The Play Console steps themselves (account,
app signing enrollment, track upload) are manual.

## License

No license file yet. Add one before publishing if you want others to
reuse this code.
