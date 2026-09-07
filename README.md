# URL Inspector

An Android app that inspects URLs received via SMS, WhatsApp, or any other
app for phishing/malware risk — before you tap them.

Paste a link, share one in from any app, or (opt-in) let the app watch
incoming SMS for links automatically. Every URL is checked against a set of
on-device heuristics (typosquatting, homograph/punycode tricks, suspicious
TLDs, IP-literal hosts, URL shorteners) and, when available, Google's Safe
Browsing reputation database — then shown as **Safe**, **Suspicious**, or
**Malicious** with the specific reasons why. The app never blocks you from
opening a link; it just tells you what it found.

## Features

- **Manual paste** — paste any URL and get an instant verdict.
- **Share-sheet integration** — share a link from WhatsApp, Messages, or any
  app directly into URL Inspector.
- **Opt-in SMS scanning** — off by default; when enabled, incoming SMS
  messages are scanned locally for links, with a notification per result.
- **Scan history** — a local, on-device log of past scans (URL, verdict,
  timestamp) — never synced or uploaded anywhere.
- **Graceful offline fallback** — if the reputation service is unreachable,
  the app still returns a verdict from on-device heuristics alone, and says
  so.
- **Privacy-first** — only the URL being checked ever leaves the device
  (to the reputation API); SMS bodies, full message content, and contact
  info are never transmitted or logged.

See [`docs/intent.md`](docs/intent.md) for the full product requirements
and [`docs/spec.md`](docs/spec.md) for the technical architecture.

## Architecture

A 3-module Gradle project:

| Module | What it is | Depends on |
|---|---|---|
| [`core`](core) | Plain Kotlin/JVM. URL normalization, detection heuristics, verdict derivation (`ScanUrlUseCase`). No Android, no network, no I/O — pure logic. | — |
| [`data`](data) | Plain Kotlin/JVM. Real implementations of `core`'s interfaces: Safe Browsing reputation lookups (Ktor), URL-shortener expansion (Ktor), scan history storage (SQLDelight). | `core` |
| [`androidApp`](androidApp) | The Android app. Jetpack Compose UI, Koin dependency injection, share-sheet + SMS-scanning platform integration. | `core`, `data` |

`core` and `data` are intentionally plain JVM modules (not Android
libraries) — they're fully unit-testable without an emulator, and keep the
detection logic portable if a non-Android client is ever built.

**Tech stack:** Kotlin 2.4, Gradle 9.7, Jetpack Compose, Koin (DI), Ktor
(HTTP client), SQLDelight (local storage), kotlinx.serialization,
kotlinx.coroutines.

## Getting Started

### Prerequisites

- **JDK 17**
- **Android SDK** (command-line tools, or via Android Studio) — platform
  35+, build-tools, platform-tools, and the emulator package if you want to
  run on a virtual device.

### 1. Clone and configure

```bash
git clone <this-repo-url>
cd url-inspector
cp local.properties.example local.properties
```

Edit `local.properties` and set `sdk.dir` to your Android SDK path, e.g.:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

(`local.properties` is gitignored — it's local machine configuration, never
committed.)

### 2. (Optional) Enable real Safe Browsing lookups

By default `safeBrowsingApiKey` is empty, and every reputation lookup fails
gracefully into a heuristics-only verdict (you'll see "Reputation check
could not be completed — showing on-device checks only" on every scan).
This is expected, working behavior, not a bug — the app is fully
functional without a key.

To enable real lookups:

1. Get a free Google Safe Browsing API v4 key: create/select a project at
   [console.cloud.google.com](https://console.cloud.google.com), enable the
   **Safe Browsing API**, then create an API key under "Credentials".
   Restrict the key to the Safe Browsing API only.
2. Add it to `local.properties`:
   ```properties
   safeBrowsingApiKey=YOUR_KEY_HERE
   ```
3. Rebuild — `local.properties` changes aren't always picked up by Gradle's
   incremental build, so force a full rebuild the first time:
   ```bash
   ./gradlew :androidApp:assembleDebug --rerun-tasks
   ```

### 3. Build

```bash
./gradlew build          # build everything
./gradlew test           # core + data unit tests
./gradlew testDebugUnitTest  # androidApp unit tests
```

### 4. Run

**On an emulator or connected device:**

```bash
./gradlew :androidApp:installDebug
adb shell am start -n com.urlinspector.app/.MainActivity
```

**Or from Android Studio:** open the project root, let Gradle sync, select
the `androidApp` run configuration, and hit Run.

To create an emulator if you don't have one:

```bash
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd -n url_inspector_avd -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
emulator -avd url_inspector_avd
```

### 5. Release build

A minified, shrunk `release` build type is configured (R8 + ProGuard
rules). It's currently signed with the debug keystore for local
build-and-run testing only — **not** suitable for distribution (see the
`TODO` in `androidApp/build.gradle.kts` for what a real release needs).

```bash
./gradlew :androidApp:assembleRelease
./gradlew :androidApp:installRelease
```

## Testing

85 unit tests across the three modules — no Android emulator required for
`core`/`data`; `androidApp`'s unit tests run on the JVM too (Robolectric is
not used; UI/platform code is instead verified manually on-device, see
`docs/plan.md`'s per-milestone notes for the reasoning).

```bash
./gradlew test testDebugUnitTest
```

There is no automated instrumented (`androidTest`) suite — every milestone
since the UI was introduced has instead been verified against a real
running emulator (paste flow, share-sheet, SMS scanning, offline fallback,
notification taps) as part of its own development process. See
`docs/plan.md` for what was manually verified and when.

## Project Documentation

- [`docs/intent.md`](docs/intent.md) — product requirements (functional
  requirements, detection rules, privacy commitments).
- [`docs/spec.md`](docs/spec.md) — technical architecture and design.
- [`docs/plan.md`](docs/plan.md) — the full implementation plan, milestone
  by milestone (M0–M6), including what was built, tested, and verified at
  each stage.

## Project Status

All six planned milestones (M0–M6) are complete: project scaffolding, core
detection domain, real data-layer integrations, Android UI, share-sheet
integration, opt-in SMS scanning, and release hardening. Not yet done, and
tracked as known follow-ups:

- A production signing keystore (currently debug-signed for local testing
  only).
- A real Safe Browsing API key baked into a distributed build (the
  mechanism is in place; supplying a key is a per-developer/per-deployment
  choice — see "Getting Started" above).
- Detection of scheme-less URLs in SMS messages (e.g. `bit.ly/x` without an
  `http://` prefix) — a known, documented gap in the SMS-scanning URL
  extractor.
- iOS support (documented as a future direction in `docs/spec.md`, not
  implemented).

## Privacy

- Only the URL being scanned is ever sent off-device, to the reputation
  API — never SMS bodies, contacts, or other message content.
- Scan history is stored locally on-device only (SQLDelight/SQLite); it is
  never synced, backed up, or uploaded (`android:allowBackup="false"`).
- SMS scanning is off by default and requires explicit opt-in; the
  permission is never requested at first launch.

See [`docs/intent.md`](docs/intent.md) §7 for the full privacy commitment.
