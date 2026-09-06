# Technical Design: Malicious URL Inspector

This document is the technical architecture and design for the
application described in [`docs/intent.md`](./intent.md) (product
requirements). For the phased build plan, see
[`docs/plan.md`](./plan.md).

## 1. Overview

The app is built with **Kotlin Multiplatform (KMP)**: all business logic
(domain models, detection heuristics, verdict derivation, reputation
lookups, storage) lives in shared modules with no Android dependency. Only
the UI shell and Android-specific integrations (share sheet, SMS scanning,
permissions) live in an Android-specific module. This means the app ships
**Android-only** for v1, but the architecture is iOS-ready: an `iosApp`
module can be added later that consumes the same `core`/`data` modules
without changing them (see §15).

## 2. Module/Project Structure

```
/core          (KMP, no platform deps)
  - domain models (ScannedUrl, Verdict, HeuristicFinding, ReputationResult, ScanHistoryEntry)
  - ReputationProvider interface
  - ScanRepository interface
  - heuristic engine (pure functions)
  - ScanUrlUseCase (orchestrates detection pipeline + verdict derivation)

/data          (KMP)
  - Ktor-based ReputationProvider implementation(s)
  - SQLDelight-backed ScanRepository implementation
  - reputation result caching

/androidApp    (Android)
  - Compose Multiplatform UI (paste screen, verdict screen, history screen, settings)
  - Share-sheet handling Activity
  - SMS ContentObserver + permission flow
  - Koin DI wiring
  - Notifications

/iosApp        (future, not built in this plan — see §15)
```

Gradle multi-module project; `core` and `data` are Android-library-free
KMP modules so they compile and unit-test on plain JVM.

## 3. High-Level Architecture

Layered / clean architecture, dependencies point inward:

```
Platform (Android: share intent, SMS observer, notifications)
        v
Presentation (Compose screens, view models)
        v
Domain (core: ScanUrlUseCase, heuristic engine, Verdict rules)
        v
Data (data: ReputationProvider impl, ScanRepository impl, cache)
```

- Presentation depends on Domain interfaces only, never on Data
  implementations directly (wired via Koin).
- Domain (`core`) has zero Android/Ktor/SQLDelight imports — it depends
  only on the `ReputationProvider` and `ScanRepository` interfaces it
  defines, which `data` implements.
- Platform code (share intent, SMS observer) is a thin adapter that
  extracts a URL/string and calls into `ScanUrlUseCase` — it contains no
  detection logic itself.

## 4. Core Data Model

```kotlin
data class ScannedUrl(val raw: String, val normalized: String, val host: String)

enum class Verdict { SAFE, SUSPICIOUS, MALICIOUS }

data class HeuristicFinding(
    val id: String,          // e.g. "typosquat", "suspicious_tld", "shortener", "ip_literal", "homograph"
    val description: String, // human-readable reason shown on verdict screen
)

data class ReputationResult(
    val matched: Boolean,
    val source: String,          // provider id, e.g. "safe-browsing"
    val checked: Boolean,        // false if lookup failed/timed out
)

data class ScanResult(
    val url: ScannedUrl,
    val verdict: Verdict,
    val heuristicFindings: List<HeuristicFinding>,
    val reputationResult: ReputationResult,
    val scannedAt: Instant,
)

data class ScanHistoryEntry(
    val id: String,
    val url: String,
    val verdict: Verdict,
    val scannedAt: Instant,
)
```

## 5. Detection Pipeline

`ScanUrlUseCase.scan(rawUrl: String): ScanResult` executes:

1. **Normalize** — parse and normalize the raw string into a `ScannedUrl`
   (reject/flag malformed input per intent.md FR2).
2. **Run heuristics and reputation lookup concurrently** (Kotlin
   coroutines, `async`/`awaitAll`):
   - Heuristics (intent.md DR3–DR7): typosquat/lookalike, suspicious TLD,
     shortener expansion (which may itself trigger a second pass of
     DR3/DR4/DR6 on the expanded URL), IP-literal host, homograph/punycode
     — each is an independent pure function returning `HeuristicFinding?`.
   - Reputation lookup (DR1) via `ReputationProvider.check(url)`, with a
     bounded timeout.
3. **Offline/failure fallback (DR2)** — if the reputation call throws or
   times out, `ReputationResult.checked = false` and the verdict is
   derived from heuristics alone; the verdict screen surfaces "reputation
   check incomplete."
4. **Derive verdict (DR8–DR10)**:
   - Reputation match → `MALICIOUS`.
   - No reputation match, but ≥1 heuristic finding → `SUSPICIOUS`.
   - No reputation match, no heuristic findings → `SAFE`.
5. **Persist** the `ScanResult` as a `ScanHistoryEntry` via
   `ScanRepository.save(...)` (FR6).
6. **Return** `ScanResult` to the caller (UI layer renders the verdict
   screen; SMS-scan caller posts a notification instead).

## 6. `ReputationProvider` Abstraction

```kotlin
interface ReputationProvider {
    val id: String
    suspend fun check(url: ScannedUrl): ReputationResult
}
```

- Contract: implementations must apply their own timeout internally and
  never throw for "no match" — only for genuine transport/auth failures,
  which the use case treats as `checked = false`.
- v1 ships one implementation, `SafeBrowsingReputationProvider` (in
  `data`), calling a Safe Browsing-style lookup API over Ktor with the
  URL's normalized form.
- Additional providers (e.g. VirusTotal-style) can be added later as new
  `ReputationProvider` implementations and composed (e.g. "any provider
  matches → malicious") without touching `core`.
- API key/config is injected via Koin, not hard-coded (see §16 open
  question on provider/key management).

## 7. Heuristic Engine

Each heuristic is a standalone, pure, independently unit-testable function
in `core`, taking a `ScannedUrl` and returning `HeuristicFinding?`:

- `TyposquatHeuristic` — compares the host against a bundled list of
  commonly impersonated brand domains using edit-distance/lookalike
  matching.
- `SuspiciousTldHeuristic` — checks the host's TLD against a bundled
  list of high-abuse/rare TLDs.
- `ShortenerHeuristic` — checks the host against a bundled list of known
  URL-shortener domains; if matched, the use case performs a `HEAD`/
  redirect-following expansion (in `data`, via Ktor) and re-runs the other
  heuristics + reputation check against the expanded URL.
- `IpLiteralHeuristic` — flags hosts that are raw IPv4/IPv6 literals.
- `HomographHeuristic` — flags hosts containing mixed-script or punycode
  (`xn--`) labels that resemble a Latin-script brand domain.

Heuristic lists (typosquat targets, TLD list, shortener domains) are
bundled as versioned resource files in `core` so they can be updated
without a full app release cycle later (out of scope for v1 to build the
update mechanism — noted in §16).

## 8. Platform Integration (Android)

- **Share-sheet**: `AndroidManifest.xml` intent-filter for
  `ACTION_SEND` + `text/plain` on a dedicated `ShareHandlerActivity`,
  which extracts the URL and calls `ScanUrlUseCase`, then shows the
  verdict screen.
- **Manual paste**: Compose screen with a text field + "Scan" button,
  calling the same use case through a shared `ScanViewModel`.
- **SMS scanning (opt-in, intent.md FR3)**:
  - Settings toggle, off by default; enabling it triggers the `READ_SMS`
    runtime permission request.
  - On grant, register a `ContentObserver` on `content://sms` in a
    foreground-safe component (a bound `Service` started while the toggle
    is on, or `WorkManager` periodic work if a persistent observer proves
    unreliable across process death — decide during implementation based
    on spot testing, see plan.md M5).
  - On a new inbound message, extract URLs (simple regex/URL-span
    detection), run `ScanUrlUseCase` for each, and post a notification
    with the verdict summary; tapping it opens the verdict screen.
  - Revoking the permission or toggling off unregisters the observer.

## 9. Storage

SQLDelight schema (shared in `data`, compiled for Android now, reusable by
iOS later):

```sql
CREATE TABLE ScanHistoryEntry (
    id TEXT NOT NULL PRIMARY KEY,
    url TEXT NOT NULL,
    verdict TEXT NOT NULL,
    scannedAt INTEGER NOT NULL
);
```

- `ScanRepository` implementation wraps SQLDelight queries: `save`,
  `getAll` (ordered by `scannedAt DESC`), `deleteById` (FR7), `clearAll`
  (FR7).
- Storage is on-device only; no sync/upload (matches intent.md §7 Privacy).

## 10. Networking & Concurrency

- **Ktor client** (KMP-compatible) for all HTTP calls (reputation lookup,
  shortener expansion), with a per-request timeout (e.g. 5s) and no
  automatic retries for v1 (a slow/unreachable provider should fail fast
  into the offline-fallback path, not stall the verdict screen).
- **Coroutines/Flow** throughout: `ScanUrlUseCase.scan` is a `suspend`
  function; heuristics and reputation lookup run via `coroutineScope` +
  `async`/`awaitAll` for concurrency.
- **Reputation result caching**: an in-memory LRU cache (keyed by
  normalized URL, short TTL e.g. 10 minutes) in `data` to avoid redundant
  API calls when the same link is scanned twice in quick succession
  (e.g. share + later manual re-check).

## 11. Dependency Injection

**Koin** (KMP-friendly, unlike Hilt which is Android-only):
- `coreModule` — binds `ScanUrlUseCase` and heuristic functions.
- `dataModule` — binds `ReputationProvider` → `SafeBrowsingReputationProvider`,
  `ScanRepository` → SQLDelight-backed impl, Ktor `HttpClient`.
- `androidModule` — binds `ScanViewModel`, Android `Context`-dependent
  pieces (SQLDelight driver, notification manager).

## 12. Permissions & Security

- `INTERNET` — required, used for reputation lookups and shortener
  expansion.
- `READ_SMS` — requested only when the user opts into SMS scanning
  (intent.md FR3); never requested at first launch.
- Only the URL is ever sent off-device (to the reputation provider); full
  SMS message bodies are read locally to extract URLs but never
  transmitted (matches intent.md §7).
- All network calls use HTTPS/TLS (enforced via Ktor engine config and
  Android network security config disallowing cleartext).
- No PII is persisted beyond `ScanHistoryEntry` (URL, verdict, timestamp),
  stored locally only.

## 13. Error Handling

- Malformed/unparseable input → validation error shown on the paste
  screen before any scan is attempted (intent.md FR2).
- Reputation provider timeout/error → `checked = false`, verdict from
  heuristics only, UI shows an "reputation check incomplete, offline?"
  notice (DR2).
- SMS permission denied or later revoked → scanning feature silently
  disables itself; toggle reflects actual permission state on screen
  resume.
- Any uncaught exception in the detection pipeline is caught at the use
  case boundary and surfaced as a generic "scan failed, try again" state
  rather than crashing the host Activity/Service.

## 14. Testing Strategy

- **`core` unit tests** (pure JVM, no Android/emulator needed): one test
  class per heuristic with positive/negative cases; verdict-derivation
  tests covering all DR8–DR10 combinations; input-normalization edge
  cases.
- **`data` tests**: `ReputationProvider` implementation tested against a
  mocked Ktor engine (success, timeout, error responses); SQLDelight
  repository tested with an in-memory driver.
- **Android instrumented tests**: share-intent handling end-to-end
  (send an `ACTION_SEND` intent, assert verdict screen renders), SMS
  `ContentObserver` behavior using a test content provider/fake SMS
  insert, permission-flow UI tests.

## 15. iOS Follow-up (documented, not implemented)

Because `core` and `data` have no Android dependency, an `iosApp` module
added later would:
- Reuse `ScanUrlUseCase`, the heuristic engine, `ReputationProvider`, and
  `ScanRepository` unchanged.
- Implement its own presentation layer (SwiftUI or Compose Multiplatform)
  and an iOS Share Extension as the input path (matching intent.md's
  Share-sheet flow).
- Have no equivalent to SMS inbox scanning — iOS provides no API for
  reading the Messages inbox, matching intent.md's iOS non-goal.

## 16. Open Technical Questions

- Which concrete reputation provider/API to launch with, and how its API
  key is provisioned/rotated (build config, remote config, or backend
  proxy to avoid embedding a raw key in the APK).
- Rate-limit and cost handling if scan volume is high (per-provider quota,
  need for a backend proxy/cache layer beyond the in-app cache in §10).
- Minimum supported Android SDK version.
- Update mechanism for the bundled heuristic lists (§7) beyond app
  releases (e.g. a remote-config-fetched list) — deferred past v1.
