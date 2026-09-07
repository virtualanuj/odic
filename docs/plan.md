# Implementation Plan: Malicious URL Inspector

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement the detailed tasks below task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Phased build plan for the application described in
[`docs/intent.md`](./intent.md) (product requirements) and
[`docs/spec.md`](./spec.md) (technical architecture and design). Written
for the engineering team to execute against.

**Spec:** [`docs/spec.md`](./spec.md) (technical architecture) and
[`docs/intent.md`](./intent.md) (product requirements — FR/DR IDs
referenced throughout).

This document currently carries a fully detailed, bite-sized task
breakdown for **M0 (project scaffolding), M1 (core domain), M2 (data
layer), M3 (Android UI & manual flow), M4 (share-sheet integration), M5
(opt-in SMS scanning), and M6 (hardening & release readiness)**. The
Android SDK, `adb`, and an emulator (`url_inspector_avd`, API 35) are
now installed on the development machine, so M3-M6 are buildable and
runnable, not just planned. M6 is the last planned milestone in this
document — see spec.md §15 for iOS follow-up, documented but not
implemented.

## Global Constraints

- Kotlin 2.4.10, Gradle 9.7.1 — the versions already installed on this
  machine; use them verbatim in build files.
- The Gradle toolchain additionally resolves JDK 17 via the Foojay resolver plugin (settings.gradle.kts) so a fresh machine auto-provisions it; gradle.properties also pins a local Homebrew JDK 17 path as a fallback for this specific dev machine — that fallback line is machine-specific and may need updating (or removing, once the Foojay resolver is confirmed sufficient) on a different machine.
- `core` is built for M0/M1 as a plain Kotlin/JVM module (`kotlin("jvm")`
  plugin) — **not** yet a full Kotlin Multiplatform module. spec.md §2
  calls for `core` to eventually be KMP with Android/iOS targets; that
  conversion is deferred to when the Android SDK is set up (ahead of M3),
  since AGP/Android SDK are not installed on this machine. This is a
  scoped, documented deviation for M0/M1 only.
- Base package for all `core` code: `com.urlinspector.core`.
- Timestamps use `java.time.Instant` for this JVM-only interim phase
  (spec.md's shared-model intent eventually implies `kotlinx-datetime`;
  that switch travels with the KMP conversion above, not before).
- Concurrency: `kotlinx-coroutines-core:1.9.0`. `ScanUrlUseCase.scan` is a
  `suspend fun`; heuristics and the reputation lookup run concurrently via
  `coroutineScope` + `async`/`await` (spec.md §5, §10).
- Verdict derivation MUST follow `docs/intent.md` DR8–DR10 exactly: a
  reputation match alone → `MALICIOUS`; no reputation match but ≥1
  heuristic finding → `SUSPICIOUS`; no reputation match and no heuristic
  findings → `SAFE`.
- Reputation provider failure or timeout MUST NOT throw out of
  `ScanUrlUseCase` — it falls back to a heuristics-only verdict with
  `ReputationResult.checked = false` (intent.md DR2).
- Every heuristic is a pure, stateless function/object taking a
  `ScannedUrl` and returning `HeuristicFinding?` — no I/O, no shared
  mutable state (spec.md §7).
- `core` in M0/M1 has **no** Android, Ktor, or SQLDelight dependencies —
  those belong to `data`/`androidApp` in later milestones (spec.md §2). Do
  not add them here.
- Out of scope for M0/M1: the `data` module, the `androidApp` module, any
  real network calls, any real database — these are M2+ below.

---

## M0 + M1 — Detailed Tasks

### Task 1: Gradle project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `core/build.gradle.kts`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a `:core` Gradle module that later tasks add Kotlin
  sources/tests to; the dependency set (`kotlinx-coroutines-core`,
  `kotlin("test-junit5")`, `kotlinx-coroutines-test`) every later task
  relies on.

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "url-inspector"

include(":core")
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
allprojects {
    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g
```

- [ ] **Step 4: Create `.gitignore`**

```gitignore
.gradle/
build/
*.iml
.idea/
local.properties
```

- [ ] **Step 5: Create `core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 6: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 9.7.1 --distribution-type bin`
Expected: creates `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.{jar,properties}`.

- [ ] **Step 7: Verify the project configures**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL` (this first run downloads the Gradle 9.7.1
distribution — requires network access once).

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore core/build.gradle.kts gradlew gradlew.bat gradle/
git commit -m "chore: scaffold Gradle project with core module"
```

---

### Task 2: Core data models

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/model/Models.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/model/ModelsTest.kt`

**Interfaces:**
- Consumes: nothing beyond the `:core` module from Task 1.
- Produces: `ScannedUrl(raw, normalized, host)`, `Verdict`
  (`SAFE`/`SUSPICIOUS`/`MALICIOUS`), `HeuristicFinding(id, description)`,
  `ReputationResult(matched, source, checked)`,
  `ScanResult(url, verdict, heuristicFindings, reputationResult,
  scannedAt)`, `ScanHistoryEntry(id, url, verdict, scannedAt)` — every
  later task in this plan uses these exact names, fields, and types.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/model/ModelsTest.kt`:

```kotlin
package com.urlinspector.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun `ScanResult holds all scan outputs together`() {
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")
        val finding = HeuristicFinding(id = "ip_literal", description = "Host is a raw IP address")
        val reputation = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val scannedAt = Instant.parse("2026-01-01T00:00:00Z")

        val result = ScanResult(
            url = url,
            verdict = Verdict.SUSPICIOUS,
            heuristicFindings = listOf(finding),
            reputationResult = reputation,
            scannedAt = scannedAt,
        )

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertEquals(1, result.heuristicFindings.size)
        assertTrue(result.reputationResult.checked)
    }

    @Test
    fun `ScanHistoryEntry stores the essentials for the history list`() {
        val entry = ScanHistoryEntry(
            id = "abc-123",
            url = "http://example.com",
            verdict = Verdict.SAFE,
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        assertEquals("abc-123", entry.id)
        assertEquals(Verdict.SAFE, entry.verdict)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.model.ModelsTest"`
Expected: `BUILD FAILED` — compilation error, `ScannedUrl`/`Verdict`/etc.
are unresolved references.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/model/Models.kt`:

```kotlin
package com.urlinspector.core.model

import java.time.Instant

data class ScannedUrl(
    val raw: String,
    val normalized: String,
    val host: String,
)

enum class Verdict {
    SAFE,
    SUSPICIOUS,
    MALICIOUS,
}

data class HeuristicFinding(
    val id: String,
    val description: String,
)

data class ReputationResult(
    val matched: Boolean,
    val source: String,
    val checked: Boolean,
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.model.ModelsTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/model/Models.kt core/src/test/kotlin/com/urlinspector/core/model/ModelsTest.kt
git commit -m "feat(core): add scan domain models"
```

---

### Task 3: URL normalization

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/UrlNormalizer.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/UrlNormalizerTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl` from Task 2.
- Produces: `fun normalizeUrl(raw: String): ScannedUrl?` — returns `null`
  for unparseable/blank input (intent.md FR2). Every later task that needs
  a `ScannedUrl` from raw text calls this function.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/UrlNormalizerTest.kt`:

```kotlin
package com.urlinspector.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlNormalizerTest {
    @Test
    fun `parses a URL that already has a scheme`() {
        val result = normalizeUrl("http://Example.com/Path")
        assertEquals("example.com", result?.host)
    }

    @Test
    fun `adds a scheme when missing`() {
        val result = normalizeUrl("example.com/path")
        assertEquals("example.com", result?.host)
        assertEquals("http://example.com/path", result?.normalized)
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(normalizeUrl("   "))
    }

    @Test
    fun `returns null when no host can be parsed`() {
        assertNull(normalizeUrl("http://"))
    }

    @Test
    fun `preserves the original raw string`() {
        val result = normalizeUrl("  http://example.com  ".trim())
        assertEquals("http://example.com", result?.raw)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.UrlNormalizerTest"`
Expected: `BUILD FAILED` — `normalizeUrl` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/UrlNormalizer.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.model.ScannedUrl
import java.net.URI

fun normalizeUrl(raw: String): ScannedUrl? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        return null
    }

    val host = uri.host?.lowercase() ?: return null
    if (host.isEmpty()) return null

    return ScannedUrl(raw = raw, normalized = uri.toString(), host = host)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.UrlNormalizerTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/UrlNormalizer.kt core/src/test/kotlin/com/urlinspector/core/UrlNormalizerTest.kt
git commit -m "feat(core): add URL normalization"
```

---

### Task 4: IP-literal heuristic

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristic.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristicTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `HeuristicFinding` from Task 2.
- Produces: `IpLiteralHeuristic.evaluate(url: ScannedUrl): HeuristicFinding?`,
  returning a finding with `id = "ip_literal"` when the host is a raw IP
  address (intent.md DR6). `ScanUrlUseCase` (Task 10) calls this.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristicTest.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IpLiteralHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags an IPv4 literal host`() {
        val finding = IpLiteralHeuristic.evaluate(urlWithHost("192.168.1.1"))
        assertEquals("ip_literal", finding?.id)
    }

    @Test
    fun `flags an IPv6 literal host`() {
        val finding = IpLiteralHeuristic.evaluate(urlWithHost("[::1]"))
        assertEquals("ip_literal", finding?.id)
    }

    @Test
    fun `does not flag a normal domain`() {
        assertNull(IpLiteralHeuristic.evaluate(urlWithHost("example.com")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.IpLiteralHeuristicTest"`
Expected: `BUILD FAILED` — `IpLiteralHeuristic` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristic.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object IpLiteralHeuristic {
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host.removePrefix("[").removeSuffix("]")
        val isIpv4 = ipv4Regex.matches(host)
        val isIpv6 = host.count { it == ':' } >= 2

        return if (isIpv4 || isIpv6) {
            HeuristicFinding(
                id = "ip_literal",
                description = "The link uses a raw IP address ($host) instead of a domain name.",
            )
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.IpLiteralHeuristicTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristic.kt core/src/test/kotlin/com/urlinspector/core/heuristics/IpLiteralHeuristicTest.kt
git commit -m "feat(core): add IP-literal heuristic"
```

---

### Task 5: Suspicious-TLD heuristic

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristic.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristicTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `HeuristicFinding` from Task 2.
- Produces: `SuspiciousTldHeuristic.evaluate(url: ScannedUrl): HeuristicFinding?`,
  returning a finding with `id = "suspicious_tld"` (intent.md DR4).
  `ScanUrlUseCase` (Task 10) also re-runs this against an
  expander-resolved URL.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristicTest.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuspiciousTldHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a high-abuse TLD`() {
        val finding = SuspiciousTldHeuristic.evaluate(urlWithHost("free-gift.tk"))
        assertEquals("suspicious_tld", finding?.id)
    }

    @Test
    fun `does not flag a common TLD`() {
        assertNull(SuspiciousTldHeuristic.evaluate(urlWithHost("example.com")))
    }

    @Test
    fun `does not flag a host with no TLD`() {
        assertNull(SuspiciousTldHeuristic.evaluate(urlWithHost("localhost")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.SuspiciousTldHeuristicTest"`
Expected: `BUILD FAILED` — `SuspiciousTldHeuristic` is an unresolved
reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristic.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object SuspiciousTldHeuristic {
    private val suspiciousTlds = setOf(
        "zip", "mov", "tk", "ml", "ga", "cf", "gq", "top", "xyz",
        "work", "click", "link", "country", "stream", "gdn", "kim",
        "loan", "men", "party", "review", "science", "trade", "win",
    )

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val tld = url.host.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (tld.isNotEmpty() && tld in suspiciousTlds) {
            HeuristicFinding(
                id = "suspicious_tld",
                description = "The domain uses a top-level domain (.$tld) that is frequently abused for scams.",
            )
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.SuspiciousTldHeuristicTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristic.kt core/src/test/kotlin/com/urlinspector/core/heuristics/SuspiciousTldHeuristicTest.kt
git commit -m "feat(core): add suspicious-TLD heuristic"
```

---

### Task 6: Typosquat / lookalike heuristic

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristic.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristicTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `HeuristicFinding` from Task 2.
- Produces: `TyposquatHeuristic.evaluate(url: ScannedUrl): HeuristicFinding?`,
  returning a finding with `id = "typosquat"` (intent.md DR3).
  `ScanUrlUseCase` (Task 10) also re-runs this against an
  expander-resolved URL.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristicTest.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TyposquatHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a one-character lookalike of a protected brand`() {
        val finding = TyposquatHeuristic.evaluate(urlWithHost("paypa1.com"))
        assertEquals("typosquat", finding?.id)
    }

    @Test
    fun `does not flag the real brand domain`() {
        assertNull(TyposquatHeuristic.evaluate(urlWithHost("paypal.com")))
    }

    @Test
    fun `does not flag an unrelated domain`() {
        assertNull(TyposquatHeuristic.evaluate(urlWithHost("totally-unrelated-shop.net")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.TyposquatHeuristicTest"`
Expected: `BUILD FAILED` — `TyposquatHeuristic` is an unresolved
reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristic.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object TyposquatHeuristic {
    private val protectedDomains = setOf(
        "paypal.com", "amazon.com", "apple.com", "google.com", "microsoft.com",
        "netflix.com", "facebook.com", "instagram.com", "whatsapp.com", "bankofamerica.com",
    )

    private const val MAX_SUSPICIOUS_DISTANCE = 2

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host
        if (host in protectedDomains) return null

        val closest = protectedDomains
            .map { it to levenshteinDistance(host, it) }
            .filter { (_, distance) -> distance in 1..MAX_SUSPICIOUS_DISTANCE }
            .minByOrNull { (_, distance) -> distance }

        return closest?.let { (brand, _) ->
            HeuristicFinding(
                id = "typosquat",
                description = "The domain \"$host\" closely resembles \"$brand\" and may be impersonating it.",
            )
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[a.length][b.length]
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.TyposquatHeuristicTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristic.kt core/src/test/kotlin/com/urlinspector/core/heuristics/TyposquatHeuristicTest.kt
git commit -m "feat(core): add typosquat heuristic"
```

---

### Task 7: Homograph / punycode heuristic

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/heuristics/HomographHeuristic.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/heuristics/HomographHeuristicTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `HeuristicFinding` from Task 2.
- Produces: `HomographHeuristic.evaluate(url: ScannedUrl): HeuristicFinding?`,
  returning a finding with `id = "homograph"` (intent.md DR7).
  `ScanUrlUseCase` (Task 10) calls this.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/heuristics/HomographHeuristicTest.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomographHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a punycode-encoded label`() {
        val finding = HomographHeuristic.evaluate(urlWithHost("xn--pple-43d.com"))
        assertEquals("homograph", finding?.id)
    }

    @Test
    fun `flags a host containing non-ASCII characters`() {
        val finding = HomographHeuristic.evaluate(urlWithHost("аpple.com"))
        assertEquals("homograph", finding?.id)
    }

    @Test
    fun `does not flag a plain ASCII domain`() {
        assertNull(HomographHeuristic.evaluate(urlWithHost("apple.com")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.HomographHeuristicTest"`
Expected: `BUILD FAILED` — `HomographHeuristic` is an unresolved
reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/heuristics/HomographHeuristic.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object HomographHeuristic {
    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        val host = url.host
        val hasPunycodeLabel = host.split('.').any { it.startsWith("xn--") }
        val hasNonAscii = host.any { it.code > 127 }

        return if (hasPunycodeLabel || hasNonAscii) {
            HeuristicFinding(
                id = "homograph",
                description = "The domain \"$host\" contains internationalized characters that can be used to visually impersonate another domain.",
            )
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.HomographHeuristicTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/heuristics/HomographHeuristic.kt core/src/test/kotlin/com/urlinspector/core/heuristics/HomographHeuristicTest.kt
git commit -m "feat(core): add homograph/punycode heuristic"
```

---

### Task 8: URL-shortener heuristic

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristic.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristicTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `HeuristicFinding` from Task 2.
- Produces: `ShortenerHeuristic.evaluate(url: ScannedUrl): HeuristicFinding?`,
  returning a finding with `id = "shortener"` (intent.md DR5).
  `ScanUrlUseCase` (Task 10) checks for a finding with this exact `id` to
  decide whether to call `UrlExpander.expand`.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristicTest.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortenerHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a known shortener domain`() {
        val finding = ShortenerHeuristic.evaluate(urlWithHost("bit.ly"))
        assertEquals("shortener", finding?.id)
    }

    @Test
    fun `does not flag a non-shortener domain`() {
        assertNull(ShortenerHeuristic.evaluate(urlWithHost("example.com")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.ShortenerHeuristicTest"`
Expected: `BUILD FAILED` — `ShortenerHeuristic` is an unresolved
reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristic.kt`:

```kotlin
package com.urlinspector.core.heuristics

import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ScannedUrl

object ShortenerHeuristic {
    private val shortenerDomains = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly",
        "is.gd", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at",
    )

    fun evaluate(url: ScannedUrl): HeuristicFinding? {
        return if (url.host in shortenerDomains) {
            HeuristicFinding(
                id = "shortener",
                description = "The link uses a URL shortener (${url.host}), which hides the real destination.",
            )
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.heuristics.ShortenerHeuristicTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristic.kt core/src/test/kotlin/com/urlinspector/core/heuristics/ShortenerHeuristicTest.kt
git commit -m "feat(core): add URL-shortener heuristic"
```

---

### Task 9: External-dependency interfaces and test fakes

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/ReputationProvider.kt`
- Create: `core/src/main/kotlin/com/urlinspector/core/UrlExpander.kt`
- Create: `core/src/main/kotlin/com/urlinspector/core/ScanRepository.kt`
- Create: `core/src/test/kotlin/com/urlinspector/core/fakes/Fakes.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/fakes/FakeScanRepositoryTest.kt`

**Interfaces:**
- Consumes: `ScannedUrl`, `ReputationResult`, `ScanHistoryEntry` from
  Task 2.
- Produces: `ReputationProvider` (`val id: String`,
  `suspend fun check(url): ReputationResult`), `UrlExpander`
  (`suspend fun expand(url): ScannedUrl`), `ScanRepository`
  (`suspend fun save(entry)`, `suspend fun getAll(): List<ScanHistoryEntry>`,
  `suspend fun deleteById(id)`, `suspend fun clearAll()`) — these three
  interfaces are what `data` implements in a later milestone. Also
  produces test doubles `FakeReputationProvider`,
  `ThrowingReputationProvider`, `FakeUrlExpander`, `FakeScanRepository` in
  `core`'s test sources, reused by Task 10's tests.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/fakes/FakeScanRepositoryTest.kt`:

```kotlin
package com.urlinspector.core.fakes

import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeScanRepositoryTest {
    @Test
    fun `save then getAll returns the saved entry`() = runTest {
        val repository = FakeScanRepository()
        val entry = ScanHistoryEntry(
            id = "1",
            url = "http://example.com",
            verdict = Verdict.SAFE,
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        repository.save(entry)

        assertEquals(listOf(entry), repository.getAll())
    }

    @Test
    fun `deleteById removes only the matching entry`() = runTest {
        val repository = FakeScanRepository()
        val keep = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val remove = ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        repository.save(keep)
        repository.save(remove)

        repository.deleteById("2")

        assertEquals(listOf(keep), repository.getAll())
    }

    @Test
    fun `clearAll empties the history`() = runTest {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        repository.clearAll()

        assertTrue(repository.getAll().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.fakes.FakeScanRepositoryTest"`
Expected: `BUILD FAILED` — `FakeScanRepository` is an unresolved
reference.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/ReputationProvider.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScannedUrl

interface ReputationProvider {
    val id: String
    suspend fun check(url: ScannedUrl): ReputationResult
}
```

`core/src/main/kotlin/com/urlinspector/core/UrlExpander.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.model.ScannedUrl

interface UrlExpander {
    suspend fun expand(url: ScannedUrl): ScannedUrl
}
```

`core/src/main/kotlin/com/urlinspector/core/ScanRepository.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.model.ScanHistoryEntry

interface ScanRepository {
    suspend fun save(entry: ScanHistoryEntry)
    suspend fun getAll(): List<ScanHistoryEntry>
    suspend fun deleteById(id: String)
    suspend fun clearAll()
}
```

`core/src/test/kotlin/com/urlinspector/core/fakes/Fakes.kt`:

```kotlin
package com.urlinspector.core.fakes

import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.UrlExpander
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.ScannedUrl

class FakeReputationProvider(
    override val id: String = "fake-reputation",
    private val result: (ScannedUrl) -> ReputationResult = {
        ReputationResult(matched = false, source = "fake-reputation", checked = true)
    },
) : ReputationProvider {
    override suspend fun check(url: ScannedUrl): ReputationResult = result(url)
}

class ThrowingReputationProvider(override val id: String = "fake-reputation") : ReputationProvider {
    override suspend fun check(url: ScannedUrl): ReputationResult {
        throw RuntimeException("simulated network failure")
    }
}

class FakeUrlExpander(
    private val expansions: Map<String, ScannedUrl> = emptyMap(),
) : UrlExpander {
    override suspend fun expand(url: ScannedUrl): ScannedUrl = expansions[url.normalized] ?: url
}

class FakeScanRepository : ScanRepository {
    val saved = mutableListOf<ScanHistoryEntry>()

    override suspend fun save(entry: ScanHistoryEntry) {
        saved.add(entry)
    }

    override suspend fun getAll(): List<ScanHistoryEntry> = saved.toList()

    override suspend fun deleteById(id: String) {
        saved.removeAll { it.id == id }
    }

    override suspend fun clearAll() {
        saved.clear()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.fakes.FakeScanRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/ReputationProvider.kt core/src/main/kotlin/com/urlinspector/core/UrlExpander.kt core/src/main/kotlin/com/urlinspector/core/ScanRepository.kt core/src/test/kotlin/com/urlinspector/core/fakes/
git commit -m "feat(core): add reputation/expander/repository interfaces and test fakes"
```

---

### Task 10: `ScanUrlUseCase` orchestration and verdict derivation

**Files:**
- Create: `core/src/main/kotlin/com/urlinspector/core/ScanUrlUseCase.kt`
- Test: `core/src/test/kotlin/com/urlinspector/core/ScanUrlUseCaseTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–9 —
  `ScannedUrl`/`Verdict`/`HeuristicFinding`/`ReputationResult`/`ScanResult`/`ScanHistoryEntry`
  (Task 2), `normalizeUrl` (Task 3),
  `TyposquatHeuristic`/`SuspiciousTldHeuristic`/`ShortenerHeuristic`/`IpLiteralHeuristic`/`HomographHeuristic`
  `.evaluate(ScannedUrl): HeuristicFinding?` (Tasks 4–8),
  `ReputationProvider`/`UrlExpander`/`ScanRepository` and the fakes in
  `com.urlinspector.core.fakes` (Task 9).
- Produces: `class ScanUrlUseCase(reputationProvider, urlExpander,
  scanRepository, reputationTimeoutMillis = 5_000L, now = Instant::now)`
  with `suspend fun scan(rawUrl: String): ScanResult`, and
  `class InvalidUrlException(raw: String) : IllegalArgumentException`.
  This is the top-level entry point later Android/UI code (M3+) calls.
- Second pass after shortener expansion: when the URL expands to a
  different destination, re-run `TyposquatHeuristic`,
  `SuspiciousTldHeuristic` **and** `IpLiteralHeuristic` against the
  expanded URL (DR3/DR4/DR6 per spec.md §5/§7), plus the reputation
  lookup for the expanded destination.

- [ ] **Step 1: Write the failing test**

`core/src/test/kotlin/com/urlinspector/core/ScanUrlUseCaseTest.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.fakes.FakeReputationProvider
import com.urlinspector.core.fakes.FakeScanRepository
import com.urlinspector.core.fakes.FakeUrlExpander
import com.urlinspector.core.fakes.ThrowingReputationProvider
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanUrlUseCaseTest {
    private val fixedNow = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `reputation match produces MALICIOUS verdict`() = runTest {
        val reputation = FakeReputationProvider(result = {
            ReputationResult(matched = true, source = "fake-reputation", checked = true)
        })
        val repository = FakeScanRepository()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), repository, now = { fixedNow })

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.MALICIOUS, result.verdict)
        assertEquals(1, repository.saved.size)
        assertEquals(Verdict.MALICIOUS, repository.saved.first().verdict)
    }

    @Test
    fun `heuristic finding without reputation match produces SUSPICIOUS verdict`() = runTest {
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://203.0.113.5/login")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "ip_literal" })
    }

    @Test
    fun `no findings and no reputation match produces SAFE verdict`() = runTest {
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
        assertTrue(result.heuristicFindings.isEmpty())
    }

    @Test
    fun `reputation failure falls back to heuristics-only verdict`() = runTest {
        val useCase = ScanUrlUseCase(
            ThrowingReputationProvider(),
            FakeUrlExpander(),
            FakeScanRepository(),
            now = { fixedNow },
        )

        val result = useCase.scan("http://example.com")

        assertEquals(Verdict.SAFE, result.verdict)
        assertFalse(result.reputationResult.checked)
    }

    @Test
    fun `shortener expansion re-checks the expanded destination`() = runTest {
        val shortUrl = requireNotNull(normalizeUrl("http://bit.ly/abc123"))
        val expandedUrl = requireNotNull(normalizeUrl("http://paypa1.com/login"))
        val expander = FakeUrlExpander(expansions = mapOf(shortUrl.normalized to expandedUrl))
        val reputation = FakeReputationProvider()
        val useCase = ScanUrlUseCase(reputation, expander, FakeScanRepository(), now = { fixedNow })

        val result = useCase.scan("http://bit.ly/abc123")

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertTrue(result.heuristicFindings.any { it.id == "shortener" })
        assertTrue(result.heuristicFindings.any { it.id == "typosquat" })
    }

    @Test
    fun `invalid input raises InvalidUrlException`() = runTest {
        val useCase = ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository(), now = { fixedNow })

        assertFailsWith<InvalidUrlException> {
            useCase.scan("   ")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.urlinspector.core.ScanUrlUseCaseTest"`
Expected: `BUILD FAILED` — `ScanUrlUseCase`/`InvalidUrlException` are
unresolved references.

- [ ] **Step 3: Write minimal implementation**

`core/src/main/kotlin/com/urlinspector/core/ScanUrlUseCase.kt`:

```kotlin
package com.urlinspector.core

import com.urlinspector.core.heuristics.HomographHeuristic
import com.urlinspector.core.heuristics.IpLiteralHeuristic
import com.urlinspector.core.heuristics.ShortenerHeuristic
import com.urlinspector.core.heuristics.SuspiciousTldHeuristic
import com.urlinspector.core.heuristics.TyposquatHeuristic
import com.urlinspector.core.model.HeuristicFinding
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.ScanResult
import com.urlinspector.core.model.ScannedUrl
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

class InvalidUrlException(raw: String) : IllegalArgumentException("Not a valid URL: $raw")

class ScanUrlUseCase(
    private val reputationProvider: ReputationProvider,
    private val urlExpander: UrlExpander,
    private val scanRepository: ScanRepository,
    private val reputationTimeoutMillis: Long = 5_000L,
    private val now: () -> Instant = Instant::now,
) {
    private val heuristics: List<(ScannedUrl) -> HeuristicFinding?> = listOf(
        TyposquatHeuristic::evaluate,
        SuspiciousTldHeuristic::evaluate,
        ShortenerHeuristic::evaluate,
        IpLiteralHeuristic::evaluate,
        HomographHeuristic::evaluate,
    )

    suspend fun scan(rawUrl: String): ScanResult {
        val url = normalizeUrl(rawUrl) ?: throw InvalidUrlException(rawUrl)

        val findings = mutableListOf<HeuristicFinding>()
        var reputationResult = ReputationResult(matched = false, source = reputationProvider.id, checked = false)

        coroutineScope {
            val heuristicDeferreds = heuristics.map { heuristic -> async { heuristic(url) } }
            val reputationDeferred = async { checkReputation(url) }

            heuristicDeferreds.forEach { deferred -> deferred.await()?.let { findings.add(it) } }
            reputationResult = reputationDeferred.await()
        }

        val sawShortener = findings.any { it.id == "shortener" }
        if (sawShortener) {
            val expandedUrl = urlExpander.expand(url)
            if (expandedUrl.normalized != url.normalized) {
                coroutineScope {
                    val typosquatDeferred = async { TyposquatHeuristic.evaluate(expandedUrl) }
                    val tldDeferred = async { SuspiciousTldHeuristic.evaluate(expandedUrl) }
                    val ipLiteralDeferred = async { IpLiteralHeuristic.evaluate(expandedUrl) }
                    val reputationDeferred = async { checkReputation(expandedUrl) }

                    typosquatDeferred.await()?.let { findings.add(it) }
                    tldDeferred.await()?.let { findings.add(it) }
                    ipLiteralDeferred.await()?.let { findings.add(it) }

                    val expandedReputation = reputationDeferred.await()
                    if (expandedReputation.matched) {
                        reputationResult = expandedReputation
                    } else if (!reputationResult.checked && expandedReputation.checked) {
                        reputationResult = expandedReputation
                    }
                }
            }
        }

        val verdict = when {
            reputationResult.matched -> Verdict.MALICIOUS
            findings.isNotEmpty() -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }

        val scannedAt = now()
        val result = ScanResult(
            url = url,
            verdict = verdict,
            heuristicFindings = findings,
            reputationResult = reputationResult,
            scannedAt = scannedAt,
        )

        scanRepository.save(
            ScanHistoryEntry(
                id = UUID.randomUUID().toString(),
                url = url.normalized,
                verdict = verdict,
                scannedAt = scannedAt,
            ),
        )

        return result
    }

    private suspend fun checkReputation(url: ScannedUrl): ReputationResult {
        val result = withTimeoutOrNull(reputationTimeoutMillis) {
            try {
                reputationProvider.check(url)
            } catch (e: Exception) {
                null
            }
        }
        return result ?: ReputationResult(matched = false, source = reputationProvider.id, checked = false)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.urlinspector.core.ScanUrlUseCaseTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, all tests across every task pass together.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/urlinspector/core/ScanUrlUseCase.kt core/src/test/kotlin/com/urlinspector/core/ScanUrlUseCaseTest.kt
git commit -m "feat(core): add ScanUrlUseCase detection pipeline and verdict derivation"
```

---

## M2 Global Constraints

These bind Tasks 11-17 below, in addition to the Global Constraints section
above (which still applies — `core` is untouched by M2).

- `:data` is built as a plain Kotlin/JVM module for M2, same interim scope
  reasoning as `:core` (spec.md §2's full KMP conversion is deferred to
  when the Android SDK is set up).
- Dependency versions — Ktor `3.5.2`, SQLDelight `2.3.2`,
  `kotlinx-serialization-json:1.9.0` — were verified resolvable against
  Kotlin 2.4.10 / Gradle 9.7.1 in this environment before this plan was
  written (checked against Maven Central metadata). If a task's build step
  hits a resolution or compatibility failure anyway, adjust to the nearest
  working version, verify with a real build, and document the deviation in
  that task's report — the same way the M1 JDK-toolchain issue was
  resolved and recorded, not guessed around.
- API key handling: `SafeBrowsingClient`/`SafeBrowsingReputationProvider`
  take the API key as a plain constructor `String` parameter. No key is
  ever hardcoded, committed, or read from a real config file in this
  milestone — M2's own tests only ever talk to Ktor's `MockEngine`, never
  the real Safe Browsing endpoint. Real key provisioning (build config,
  remote config, etc. — spec.md §16 open question) is androidApp's concern
  in a later milestone.
- Timeouts: the `data` layer does not configure its own Ktor request
  timeouts in M2. `core.ScanUrlUseCase` (M1) already wraps both
  `reputationProvider.check(...)` and `urlExpander.expand(...)` in
  `withTimeoutOrNull` via its `runGuarded` helper, which cooperatively
  cancels a hung network call at the use-case boundary — that's the
  intended single place this is handled (spec.md §10, §13), so don't
  re-add timeout config in `data` and don't treat its absence there as a
  gap.
- SQLDelight code generation: the exact generated `*Queries` accessor
  property name on the generated `Database` class depends on the `.sq`
  file name and SQLDelight's naming convention. Task 12 must verify the
  real generated name (build and inspect
  `data/build/generated/sqldelight/...`, or let a compile error reveal it)
  rather than assuming the name given in this plan is exactly right.

---

## M2 — Data Layer: Detailed Tasks

### Task 11: `:data` module scaffolding

**Files:**
- Modify: `settings.gradle.kts` — add `include(":data")`
- Create: `data/build.gradle.kts`

**Interfaces:**
- Consumes: the `:core` module (Tasks 1-10) as a project dependency.
- Produces: a `:data` Gradle module with Ktor, SQLDelight, and
  kotlinx-serialization wired, that later tasks add Kotlin sources/tests
  to.

- [ ] **Step 1: Add `:data` to `settings.gradle.kts`**

Edit the existing `settings.gradle.kts` so the `include(...)` line reads:

```kotlin
include(":core")
include(":data")
```

(Leave the `pluginManagement`/`plugins` Foojay-resolver blocks and
`rootProject.name` line exactly as they are — only the `include` line
changes.)

- [ ] **Step 2: Create `data/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("app.cash.sqldelight") version "2.3.2"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    implementation("app.cash.sqldelight:sqlite-driver:2.3.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.2")
}

sqldelight {
    databases {
        create("UrlInspectorDatabase") {
            packageName.set("com.urlinspector.data.db")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Verify the module configures**

Run: `./gradlew :data:help`
Expected: `BUILD SUCCESSFUL`. This resolves all the new plugins and
dependencies (Ktor, SQLDelight, kotlinx-serialization) against Kotlin
2.4.10/Gradle 9.7.1 for the first time — if it fails with a version
conflict or "plugin not found", that's the signal to adjust versions per
the M2 Global Constraints note above. Record whatever you had to change.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts data/build.gradle.kts
git commit -m "chore: scaffold :data module with Ktor/SQLDelight/serialization"
```

---

### Task 12: SQLDelight schema and `ScanRepository` implementation

**Files:**
- Create: `data/src/main/sqldelight/com/urlinspector/data/db/ScanHistory.sq`
- Create: `data/src/main/kotlin/com/urlinspector/data/db/SqlDelightScanRepository.kt`
- Test: `data/src/test/kotlin/com/urlinspector/data/db/SqlDelightScanRepositoryTest.kt`

**Interfaces:**
- Consumes: `core.ScanRepository`, `core.model.ScanHistoryEntry`,
  `core.model.Verdict` (from `:core`, Tasks 2 and 9).
- Produces: `SqlDelightScanRepository(database: UrlInspectorDatabase) : ScanRepository`
  — a real, disk/in-memory-SQLite-backed implementation. Task 17's
  integration test constructs this directly.

- [ ] **Step 1: Write the failing test**

`data/src/test/kotlin/com/urlinspector/data/db/SqlDelightScanRepositoryTest.kt`:

```kotlin
package com.urlinspector.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightScanRepositoryTest {

    private fun newRepository(): SqlDelightScanRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UrlInspectorDatabase.Schema.create(driver)
        return SqlDelightScanRepository(UrlInspectorDatabase(driver))
    }

    @Test
    fun `save then getAll returns entries newest first`() = runTest {
        val repository = newRepository()
        val older = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val newer = ScanHistoryEntry("2", "http://b.com", Verdict.MALICIOUS, Instant.parse("2026-01-02T00:00:00Z"))

        repository.save(older)
        repository.save(newer)

        assertEquals(listOf(newer, older), repository.getAll())
    }

    @Test
    fun `deleteById removes only the matching entry`() = runTest {
        val repository = newRepository()
        val keep = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val remove = ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        repository.save(keep)
        repository.save(remove)

        repository.deleteById("2")

        assertEquals(listOf(keep), repository.getAll())
    }

    @Test
    fun `clearAll empties the history`() = runTest {
        val repository = newRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        repository.clearAll()

        assertTrue(repository.getAll().isEmpty())
    }
}
```

- [ ] **Step 2: Create the SQLDelight schema first (needed before the test can even compile)**

`data/src/main/sqldelight/com/urlinspector/data/db/ScanHistory.sq`:

```sql
CREATE TABLE ScanHistoryRow (
    id TEXT NOT NULL PRIMARY KEY,
    url TEXT NOT NULL,
    verdict TEXT NOT NULL,
    scannedAt INTEGER NOT NULL
);

insertEntry:
INSERT INTO ScanHistoryRow(id, url, verdict, scannedAt)
VALUES (?, ?, ?, ?);

selectAll:
SELECT * FROM ScanHistoryRow ORDER BY scannedAt DESC;

deleteById:
DELETE FROM ScanHistoryRow WHERE id = ?;

deleteAll:
DELETE FROM ScanHistoryRow;
```

- [ ] **Step 3: Run codegen and confirm the generated accessor name**

Run: `./gradlew :data:generateMainUrlInspectorDatabaseInterface`
Then inspect the generated `Queries` accessor property on the generated
`UrlInspectorDatabase` class (search
`data/build/generated/sqldelight/code/UrlInspectorDatabase/` for the
generated `UrlInspectorDatabase.kt`). Because the `.sq` file above is
named `ScanHistory.sq`, SQLDelight is expected to generate an accessor
named `scanHistoryQueries` on the database object (derived from the file
name, not the table name) — confirm this is the actual generated name
before writing Step 4. If it's different, use the real name throughout
Step 4 instead of guessing.

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :data:test --tests "com.urlinspector.data.db.SqlDelightScanRepositoryTest"`
Expected: `BUILD FAILED` — `SqlDelightScanRepository` is an unresolved
reference (the schema/generated types from Step 2-3 now exist, but the
repository class implementing `ScanRepository` doesn't yet).

- [ ] **Step 5: Write minimal implementation**

`data/src/main/kotlin/com/urlinspector/data/db/SqlDelightScanRepository.kt`
(adjust `scanHistoryQueries` below if Step 3 found a different generated
name):

```kotlin
package com.urlinspector.data.db

import com.urlinspector.core.ScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class SqlDelightScanRepository(
    private val database: UrlInspectorDatabase,
) : ScanRepository {

    override suspend fun save(entry: ScanHistoryEntry) = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.insertEntry(
            id = entry.id,
            url = entry.url,
            verdict = entry.verdict.name,
            scannedAt = entry.scannedAt.toEpochMilli(),
        )
    }

    override suspend fun getAll(): List<ScanHistoryEntry> = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.selectAll().executeAsList().map { row ->
            ScanHistoryEntry(
                id = row.id,
                url = row.url,
                verdict = Verdict.valueOf(row.verdict),
                scannedAt = Instant.ofEpochMilli(row.scannedAt),
            )
        }
    }

    override suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.deleteById(id)
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.deleteAll()
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :data:test --tests "com.urlinspector.data.db.SqlDelightScanRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 7: Commit**

```bash
git add data/src/main/sqldelight/com/urlinspector/data/db/ScanHistory.sq data/src/main/kotlin/com/urlinspector/data/db/SqlDelightScanRepository.kt data/src/test/kotlin/com/urlinspector/data/db/SqlDelightScanRepositoryTest.kt
git commit -m "feat(data): add SQLDelight-backed ScanRepository"
```

---

### Task 13: In-memory reputation cache

**Files:**
- Create: `data/src/main/kotlin/com/urlinspector/data/reputation/ReputationCache.kt`
- Test: `data/src/test/kotlin/com/urlinspector/data/reputation/ReputationCacheTest.kt`

**Interfaces:**
- Consumes: `core.model.ReputationResult` (from `:core`, Task 2).
- Produces: `ReputationCache(maxSize: Int = 200, ttlMillis: Long = 600_000)`
  with `fun get(key: String, now: Instant): ReputationResult?` and
  `fun put(key: String, result: ReputationResult, now: Instant)`. Task 15
  (`SafeBrowsingReputationProvider`) consumes this directly.

- [ ] **Step 1: Write the failing test**

`data/src/test/kotlin/com/urlinspector/data/reputation/ReputationCacheTest.kt`:

```kotlin
package com.urlinspector.data.reputation

import com.urlinspector.core.model.ReputationResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReputationCacheTest {
    @Test
    fun `a fresh entry is returned before it expires`() {
        val cache = ReputationCache(ttlMillis = 10_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val result = ReputationResult(matched = true, source = "safe-browsing", checked = true)

        cache.put("http://example.com", result, now)

        assertEquals(result, cache.get("http://example.com", now.plusMillis(5_000)))
    }

    @Test
    fun `an expired entry is not returned`() {
        val cache = ReputationCache(ttlMillis = 10_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val result = ReputationResult(matched = true, source = "safe-browsing", checked = true)

        cache.put("http://example.com", result, now)

        assertNull(cache.get("http://example.com", now.plusMillis(10_001)))
    }

    @Test
    fun `exceeding max size evicts the least recently used entry`() {
        val cache = ReputationCache(maxSize = 2, ttlMillis = 60_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val a = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val b = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val c = ReputationResult(matched = false, source = "safe-browsing", checked = true)

        cache.put("a", a, now)
        cache.put("b", b, now)
        cache.put("c", c, now)

        assertNull(cache.get("a", now))
        assertEquals(b, cache.get("b", now))
        assertEquals(c, cache.get("c", now))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.ReputationCacheTest"`
Expected: `BUILD FAILED` — `ReputationCache` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`data/src/main/kotlin/com/urlinspector/data/reputation/ReputationCache.kt`:

```kotlin
package com.urlinspector.data.reputation

import com.urlinspector.core.model.ReputationResult
import java.time.Instant

class ReputationCache(
    private val maxSize: Int = 200,
    private val ttlMillis: Long = 10 * 60 * 1000,
) {
    private data class Entry(val result: ReputationResult, val cachedAt: Instant)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(key: String, now: Instant): ReputationResult? {
        val entry = entries[key] ?: return null
        val age = now.toEpochMilli() - entry.cachedAt.toEpochMilli()
        if (age > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.result
    }

    @Synchronized
    fun put(key: String, result: ReputationResult, now: Instant) {
        entries[key] = Entry(result, now)
        if (entries.size > maxSize) {
            val oldestKey = entries.keys.iterator().next()
            entries.remove(oldestKey)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.ReputationCacheTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/kotlin/com/urlinspector/data/reputation/ReputationCache.kt data/src/test/kotlin/com/urlinspector/data/reputation/ReputationCacheTest.kt
git commit -m "feat(data): add in-memory reputation result cache"
```

---

### Task 14: Safe Browsing DTOs and low-level Ktor client

**Files:**
- Create: `data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingModels.kt`
- Create: `data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingClient.kt`
- Test: `data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingClientTest.kt`

**Interfaces:**
- Consumes: nothing from `:core` — this is a standalone HTTP client
  wrapper.
- Produces: `SafeBrowsingClient(httpClient: HttpClient, apiKey: String, baseUrl: String = ...)`
  with `suspend fun findThreatMatches(url: String): List<SafeBrowsingThreatMatch>`.
  Task 15 (`SafeBrowsingReputationProvider`) consumes this directly.

- [ ] **Step 1: Write the failing test**

`data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingClientTest.kt`:

```kotlin
package com.urlinspector.data.reputation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SafeBrowsingClientTest {

    private fun clientWith(responseBody: String, status: HttpStatusCode = HttpStatusCode.OK): SafeBrowsingClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseBody),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        return SafeBrowsingClient(httpClient, apiKey = "test-key")
    }

    @Test
    fun `returns matches when the API reports a threat`() = runTest {
        val client = clientWith("""{"matches":[{"threatType":"MALWARE"}]}""")

        val matches = client.findThreatMatches("http://malicious.example.com")

        assertEquals(1, matches.size)
        assertEquals("MALWARE", matches.first().threatType)
    }

    @Test
    fun `returns an empty list when the API reports no threats`() = runTest {
        val client = clientWith("""{}""")

        val matches = client.findThreatMatches("http://example.com")

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `throws when the API returns an error status`() = runTest {
        val client = clientWith("""{"error":"bad request"}""", status = HttpStatusCode.BadRequest)

        assertFailsWith<Exception> {
            client.findThreatMatches("http://example.com")
        }
    }
}
```

Note: the third test relies on `expectSuccess = true` (set in
`clientWith` above) — this makes Ktor throw on a non-2xx response
instead of silently returning it. Carry `expectSuccess = true` into any
other `HttpClient` you construct in this and later tasks' tests/code where
the same throw-on-error behavior is expected.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.SafeBrowsingClientTest"`
Expected: `BUILD FAILED` — `SafeBrowsingClient`/`SafeBrowsingThreatMatch`
are unresolved references.

- [ ] **Step 3: Write minimal implementation**

`data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingModels.kt`:

```kotlin
package com.urlinspector.data.reputation

import kotlinx.serialization.Serializable

@Serializable
data class SafeBrowsingClientInfo(
    val clientId: String,
    val clientVersion: String,
)

@Serializable
data class SafeBrowsingThreatEntry(
    val url: String,
)

@Serializable
data class SafeBrowsingThreatInfo(
    val threatTypes: List<String>,
    val platformTypes: List<String>,
    val threatEntryTypes: List<String>,
    val threatEntries: List<SafeBrowsingThreatEntry>,
)

@Serializable
data class SafeBrowsingFindRequest(
    val client: SafeBrowsingClientInfo,
    val threatInfo: SafeBrowsingThreatInfo,
)

@Serializable
data class SafeBrowsingThreatMatch(
    val threatType: String,
)

@Serializable
data class SafeBrowsingFindResponse(
    val matches: List<SafeBrowsingThreatMatch>? = null,
)
```

`data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingClient.kt`:

```kotlin
package com.urlinspector.data.reputation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType

private const val DEFAULT_BASE_URL = "https://safebrowsing.googleapis.com/v4/threatMatches:find"
private val DEFAULT_THREAT_TYPES = listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE")

class SafeBrowsingClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun findThreatMatches(url: String): List<SafeBrowsingThreatMatch> {
        val request = SafeBrowsingFindRequest(
            client = SafeBrowsingClientInfo(clientId = "url-inspector", clientVersion = "1.0.0"),
            threatInfo = SafeBrowsingThreatInfo(
                threatTypes = DEFAULT_THREAT_TYPES,
                platformTypes = listOf("ANY_PLATFORM"),
                threatEntryTypes = listOf("URL"),
                threatEntries = listOf(SafeBrowsingThreatEntry(url = url)),
            ),
        )

        val response: SafeBrowsingFindResponse = httpClient.post {
            url("$baseUrl?key=$apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        return response.matches.orEmpty()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.SafeBrowsingClientTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingModels.kt data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingClient.kt data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingClientTest.kt
git commit -m "feat(data): add Safe Browsing DTOs and Ktor client"
```

---

### Task 15: `SafeBrowsingReputationProvider` (implements `core.ReputationProvider`)

**Files:**
- Create: `data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProvider.kt`
- Test: `data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProviderTest.kt`

**Interfaces:**
- Consumes: `core.ReputationProvider`, `core.model.ReputationResult`,
  `core.model.ScannedUrl` (from `:core`), `SafeBrowsingClient` (Task 14),
  `ReputationCache` (Task 13).
- Produces: `SafeBrowsingReputationProvider(client: SafeBrowsingClient, cache: ReputationCache, id: String = "safe-browsing", now: () -> Instant = Instant::now) : ReputationProvider`.
  Task 17's integration test constructs this directly; it's the concrete
  `ReputationProvider` a future androidApp DI module will bind.

- [ ] **Step 1: Write the failing test**

`data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProviderTest.kt`:

```kotlin
package com.urlinspector.data.reputation

import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeBrowsingReputationProviderTest {

    @Test
    fun `a threat match is reported and cached`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ ->
            requestCount++
            respond(
                content = ByteReadChannel("""{"matches":[{"threatType":"MALWARE"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val client = SafeBrowsingClient(httpClient, apiKey = "test-key")
        val cache = ReputationCache()
        val fixedNow = Instant.parse("2026-01-01T00:00:00Z")
        val provider = SafeBrowsingReputationProvider(client, cache, now = { fixedNow })
        val url = ScannedUrl(raw = "http://malicious.example.com", normalized = "http://malicious.example.com/", host = "malicious.example.com")

        val first = provider.check(url)
        val second = provider.check(url)

        assertTrue(first.matched)
        assertEquals(first, second)
        assertEquals(1, requestCount)
    }

    @Test
    fun `no threat match is reported as unmatched`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val client = SafeBrowsingClient(httpClient, apiKey = "test-key")
        val provider = SafeBrowsingReputationProvider(client, ReputationCache())
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")

        val result = provider.check(url)

        assertEquals(false, result.matched)
        assertTrue(result.checked)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.SafeBrowsingReputationProviderTest"`
Expected: `BUILD FAILED` — `SafeBrowsingReputationProvider` is an
unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProvider.kt`:

```kotlin
package com.urlinspector.data.reputation

import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScannedUrl
import java.time.Instant

class SafeBrowsingReputationProvider(
    private val client: SafeBrowsingClient,
    private val cache: ReputationCache,
    override val id: String = "safe-browsing",
    private val now: () -> Instant = Instant::now,
) : ReputationProvider {

    override suspend fun check(url: ScannedUrl): ReputationResult {
        val cacheKey = url.normalized
        val currentTime = now()

        cache.get(cacheKey, currentTime)?.let { return it }

        val matches = client.findThreatMatches(url.normalized)
        val result = ReputationResult(matched = matches.isNotEmpty(), source = id, checked = true)

        cache.put(cacheKey, result, currentTime)
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :data:test --tests "com.urlinspector.data.reputation.SafeBrowsingReputationProviderTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProvider.kt data/src/test/kotlin/com/urlinspector/data/reputation/SafeBrowsingReputationProviderTest.kt
git commit -m "feat(data): add SafeBrowsingReputationProvider wiring client + cache"
```

---

### Task 16: `HttpUrlExpander` (implements `core.UrlExpander`)

**Files:**
- Create: `data/src/main/kotlin/com/urlinspector/data/expansion/HttpUrlExpander.kt`
- Test: `data/src/test/kotlin/com/urlinspector/data/expansion/HttpUrlExpanderTest.kt`

**Interfaces:**
- Consumes: `core.UrlExpander`, `core.normalizeUrl`, `core.model.ScannedUrl`
  (from `:core`, Tasks 3 and 9).
- Produces: `HttpUrlExpander(httpClient: HttpClient) : UrlExpander`. Task
  17's integration test constructs this directly.

- [ ] **Step 1: Write the failing test**

`data/src/test/kotlin/com/urlinspector/data/expansion/HttpUrlExpanderTest.kt`:

```kotlin
package com.urlinspector.data.expansion

import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpUrlExpanderTest {

    @Test
    fun `follows a redirect chain to the final destination`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                "http://bit.ly/abc123" -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "http://real-destination.example.com/"),
                )
                else -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                )
            }
        }
        val httpClient = HttpClient(engine) { followRedirects = false }
        val expander = HttpUrlExpander(httpClient)
        val shortUrl = ScannedUrl(raw = "http://bit.ly/abc123", normalized = "http://bit.ly/abc123", host = "bit.ly")

        val result = expander.expand(shortUrl)

        assertEquals("real-destination.example.com", result.host)
    }

    @Test
    fun `returns the same URL when there is no redirect`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = ByteReadChannel.Empty, status = HttpStatusCode.OK)
        }
        val httpClient = HttpClient(engine) { followRedirects = false }
        val expander = HttpUrlExpander(httpClient)
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")

        val result = expander.expand(url)

        assertEquals("example.com", result.host)
    }
}
```

Note: `followRedirects = false` on the `HttpClient` is required so a 3xx
response surfaces to `HttpUrlExpander` instead of being transparently
followed by Ktor's engine.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:test --tests "com.urlinspector.data.expansion.HttpUrlExpanderTest"`
Expected: `BUILD FAILED` — `HttpUrlExpander` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`data/src/main/kotlin/com/urlinspector/data/expansion/HttpUrlExpander.kt`:

```kotlin
package com.urlinspector.data.expansion

import com.urlinspector.core.UrlExpander
import com.urlinspector.core.normalizeUrl
import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isRedirect

private const val MAX_REDIRECTS = 5

class HttpUrlExpander(
    private val httpClient: HttpClient,
) : UrlExpander {

    override suspend fun expand(url: ScannedUrl): ScannedUrl {
        var current = url.normalized
        repeat(MAX_REDIRECTS) {
            val response: HttpResponse = httpClient.head(current)
            if (!response.status.isRedirect()) {
                return normalizeUrl(current) ?: url
            }
            val location = response.headers[HttpHeaders.Location] ?: return normalizeUrl(current) ?: url
            current = location
        }
        return normalizeUrl(current) ?: url
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :data:test --tests "com.urlinspector.data.expansion.HttpUrlExpanderTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/kotlin/com/urlinspector/data/expansion/HttpUrlExpander.kt data/src/test/kotlin/com/urlinspector/data/expansion/HttpUrlExpanderTest.kt
git commit -m "feat(data): add HttpUrlExpander for shortener resolution"
```

---

### Task 17: End-to-end integration test — `core.ScanUrlUseCase` over real M2 implementations

**Files:**
- Test: `data/src/test/kotlin/com/urlinspector/data/ScanUrlUseCaseIntegrationTest.kt`

**Interfaces:**
- Consumes: `core.ScanUrlUseCase`, `core.model.Verdict` (from `:core`,
  Task 10), and every concrete `:data` implementation from Tasks 12, 15,
  16 (`SqlDelightScanRepository`, `SafeBrowsingReputationProvider`,
  `HttpUrlExpander`).
- Produces: nothing new for other tasks — this is the M2 exit-criteria
  proof that the M1 domain and M2 data layer compose correctly end to
  end, per docs/spec.md's M2 exit criteria.

- [ ] **Step 1: Write the test**

`data/src/test/kotlin/com/urlinspector/data/ScanUrlUseCaseIntegrationTest.kt`:

```kotlin
package com.urlinspector.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import com.urlinspector.data.db.SqlDelightScanRepository
import com.urlinspector.data.db.UrlInspectorDatabase
import com.urlinspector.data.expansion.HttpUrlExpander
import com.urlinspector.data.reputation.ReputationCache
import com.urlinspector.data.reputation.SafeBrowsingClient
import com.urlinspector.data.reputation.SafeBrowsingReputationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanUrlUseCaseIntegrationTest {

    @Test
    fun `a known-malicious URL is scanned end-to-end and persisted to real storage`() = runTest {
        val reputationEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"matches":[{"threatType":"SOCIAL_ENGINEERING"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val reputationHttpClient = HttpClient(reputationEngine) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        val reputationProvider = SafeBrowsingReputationProvider(
            SafeBrowsingClient(reputationHttpClient, apiKey = "test-key"),
            ReputationCache(),
        )

        val expanderEngine = MockEngine { _ -> respond(content = ByteReadChannel.Empty, status = HttpStatusCode.OK) }
        val expanderHttpClient = HttpClient(expanderEngine) { followRedirects = false }
        val urlExpander = HttpUrlExpander(expanderHttpClient)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UrlInspectorDatabase.Schema.create(driver)
        val repository = SqlDelightScanRepository(UrlInspectorDatabase(driver))

        val useCase = ScanUrlUseCase(reputationProvider, urlExpander, repository)

        val result = useCase.scan("http://phishing.example.com/login")

        assertEquals(Verdict.MALICIOUS, result.verdict)

        val history = repository.getAll()
        assertEquals(1, history.size)
        assertEquals(Verdict.MALICIOUS, history.first().verdict)
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :data:test --tests "com.urlinspector.data.ScanUrlUseCaseIntegrationTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 3: Run the full `:data` suite**

Run: `./gradlew :data:test`
Expected: `BUILD SUCCESSFUL`, all tests across Tasks 12-17 pass together
(around 14 tests total in `:data`).

- [ ] **Step 4: Run the whole project's tests**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` — `:core:test` (38 tests) and `:data:test`
(the new tests) both green.

- [ ] **Step 5: Commit**

```bash
git add data/src/test/kotlin/com/urlinspector/data/ScanUrlUseCaseIntegrationTest.kt
git commit -m "test(data): add end-to-end ScanUrlUseCase integration test over real M2 implementations"
```

---

## M3 Global Constraints

These bind Tasks 18-25 below.

- **Android SDK is now installed** on this machine: SDK platform 35,
  build-tools 35.0.0, platform-tools, `cmdline-tools;latest`, the emulator
  package, and a `google_apis`/arm64-v8a system image — all via Homebrew's
  `android-commandlinetools` cask. `ANDROID_HOME` /
  `local.properties`'s `sdk.dir` point at
  `/opt/homebrew/share/android-commandlinetools`. An AVD named
  `url_inspector_avd` (Pixel 6 profile, API 35) exists and has been
  verified to boot (`adb devices` shows `emulator-5554`).
- **KMP deferred further, scoped decision confirmed for M3**: `core` and
  `data` stay plain Kotlin/JVM modules (as they have been since M0/M1).
  `androidApp` is a normal Android application module that depends on
  them as ordinary JVM library projects — this works fine with the
  Android Gradle Plugin. `androidApp` uses plain **Jetpack Compose**
  (`androidx.compose.*`), not Compose Multiplatform, since there is no
  KMP target to share UI code with yet. spec.md §1/§2's "Compose
  Multiplatform" framing is aspirational for a future iOS target: revisit
  if/when that's actually prioritized.
- Base package for all `androidApp` code: `com.urlinspector.app`.
  `applicationId` / manifest package: `com.urlinspector.app`.
- Dependency versions were checked against Google's and Maven Central's
  metadata before writing this plan (same rigor as M2): AGP `9.4.0`
  (stable; `9.5.x` was alpha-only at write time), Kotlin `2.4.10` (already
  in use), `androidx.activity:activity-compose:1.13.0`,
  `androidx.lifecycle:*:2.11.0`, `androidx.navigation:navigation-compose:2.10.0`,
  `androidx.compose:compose-bom:2026.08.00`, `androidx.core:core-ktx:1.19.0`,
  `io.insert-koin:koin-android`/`koin-androidx-compose:4.2.2`,
  `app.cash.sqldelight:android-driver:2.3.2` (matching the version already
  used in `:data`), Ktor `3.5.2` (matching `:data`). If a task's build step
  hits a resolution/compatibility failure anyway, adjust to the nearest
  working version, verify with a real build, and document the deviation —
  same practice as every prior milestone.
- `minSdk = 26`, `compileSdk = targetSdk = 35`, Java/Kotlin JVM target 17
  (matching `core`/`data`'s toolchain).
- **Inherits from M2**: the DI wiring that constructs `HttpClient`
  instances for `:data` must (a) `install(HttpTimeout)` with a bounded
  request timeout — M2 deliberately built `SafeBrowsingClient`/
  `HttpUrlExpander` with no client-level timeout, relying entirely on
  `core.ScanUrlUseCase`'s `withTimeoutOrNull` wrapper, which only protects
  callers that go through `ScanUrlUseCase`; (b) construct two
  separately-configured `HttpClient` instances, one with
  `followRedirects = false` for `HttpUrlExpander` and one with
  `ContentNegotiation`/`expectSuccess = true` for `SafeBrowsingClient` —
  see the KDoc on each class in `:data`.
- **No real Safe Browsing API key**: the DI module wires
  `SafeBrowsingReputationProvider` with an empty-string API key by
  default (a `TODO`-documented placeholder, never a real credential).
  This is intentional — a real key call will fail (400/403), which
  `core.ScanUrlUseCase`'s `runGuarded` already turns into a graceful
  heuristics-only fallback (DR2). This is the correct, demonstrable
  behavior for now; wiring a real key is a future, separate concern
  (build config / secrets management), not part of this plan.
- **Scope-narrowing decision, stated explicitly**: this M3 pass does
  **not** add Compose UI instrumented tests (`androidTest`) for the three
  screens — writing correct Compose UI test code blind (without iterating
  against a running emulator first) has a high chance of being subtly
  wrong in ways that waste more time than they save. Screens are verified
  by (a) successful compilation (`assembleDebug`) after each task, and
  (b) a real on-device/emulator run in Task 25, which is the actual proof
  this milestone's exit criteria require. `ScanViewModel`'s state-machine
  logic (the one piece with real branching logic worth unit-testing) DOES
  get a plain JVM unit test in Task 20. Revisit `androidTest` coverage in
  a later pass once the UI has stabilized against real device behavior.
- Out of scope for M3: share-sheet integration (M4), SMS scanning (M5),
  release build/signing config (M6).

---

## M3 — Android UI & Manual Flow: Detailed Tasks

### Task 18: `androidApp` module scaffolding

**Files:**
- Modify: `settings.gradle.kts` — add `include(":androidApp")`
- Create: `androidApp/build.gradle.kts`
- Create: `androidApp/src/main/AndroidManifest.xml`
- Create: `androidApp/src/main/res/values/themes.xml`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt`

**Interfaces:**
- Consumes: `:core` and `:data` as project dependencies.
- Produces: a buildable, installable (empty-screen) Android app —
  `com.urlinspector.app.MainActivity` and `com.urlinspector.app.UrlInspectorApp`
  (the `Application` subclass, Koin wiring added in Task 19). Later tasks
  add real Compose content to `MainActivity`.

- [ ] **Step 1: Add `:androidApp` to `settings.gradle.kts`**

```kotlin
include(":core")
include(":data")
include(":androidApp")
```

- [ ] **Step 2: Create `androidApp/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "9.4.0"
    kotlin("android") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
}

android {
    namespace = "com.urlinspector.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.urlinspector.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.insert-koin:koin-android:4.2.2")
    implementation("io.insert-koin:koin-androidx-compose:4.2.2")

    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create `androidApp/src/main/AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".UrlInspectorApp"
        android:allowBackup="true"
        android:label="URL Inspector"
        android:theme="@style/Theme.UrlInspector">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 4: Create `androidApp/src/main/res/values/themes.xml`**

```xml
<resources>
    <style name="Theme.UrlInspector" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 5: Create `androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt`**

A minimal `Application` subclass for now — Task 19 adds Koin startup here.

```kotlin
package com.urlinspector.app

import android.app.Application

class UrlInspectorApp : Application()
```

- [ ] **Step 6: Create `androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt`**

A minimal placeholder screen for now — later tasks replace the `setContent` body.

```kotlin
package com.urlinspector.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text("URL Inspector")
                }
            }
        }
    }
}
```

- [ ] **Step 7: Verify the module builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`, produces
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`. This is the
first real Android/AGP/Compose build in this project — if any dependency
version fails to resolve or the AGP/Kotlin-compose-plugin combination
errors, diagnose and adjust per the Global Constraints note above,
verify with a real rebuild, and document what changed.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts androidApp/build.gradle.kts androidApp/src/main/AndroidManifest.xml androidApp/src/main/res/values/themes.xml androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt
git commit -m "chore: scaffold androidApp module with Compose"
```

---

### Task 19: Koin DI wiring

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt`

**Interfaces:**
- Consumes: `core.ScanUrlUseCase`, `core.ReputationProvider`,
  `core.UrlExpander`, `core.ScanRepository` (from `:core`);
  `data.db.SqlDelightScanRepository`, `data.db.UrlInspectorDatabase`,
  `data.expansion.HttpUrlExpander`, `data.reputation.ReputationCache`,
  `data.reputation.SafeBrowsingClient`,
  `data.reputation.SafeBrowsingReputationProvider`,
  `data.reputation.safeBrowsingJson` (from `:data`, all already built).
- Produces: a Koin module (`appModule`) that later tasks' `ViewModel`s
  and Composables resolve `ScanUrlUseCase`/`core.ScanRepository` through
  (via `koinViewModel()` in Compose, per Task 20/23).

- [ ] **Step 1: Create `androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt`**

```kotlin
package com.urlinspector.app.di

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.UrlExpander
import com.urlinspector.data.db.SqlDelightScanRepository
import com.urlinspector.data.db.UrlInspectorDatabase
import com.urlinspector.data.expansion.HttpUrlExpander
import com.urlinspector.data.reputation.ReputationCache
import com.urlinspector.data.reputation.SafeBrowsingClient
import com.urlinspector.data.reputation.SafeBrowsingReputationProvider
import com.urlinspector.data.reputation.safeBrowsingJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.qualifier.named
import org.koin.dsl.module

// TODO(future milestone): real key provisioning (build config / secrets
// management) is out of scope for M3. An empty key means every reputation
// lookup fails (400/403), which ScanUrlUseCase.runGuarded already turns
// into a graceful heuristics-only fallback — this is intended, working
// DR2 behavior for now, not a bug.
private const val SAFE_BROWSING_API_KEY = ""

private const val REPUTATION_HTTP_CLIENT = "reputationHttpClient"
private const val EXPANDER_HTTP_CLIENT = "expanderHttpClient"
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

val appModule = module {
    single(named(REPUTATION_HTTP_CLIENT)) {
        HttpClient(CIO) {
            expectSuccess = true
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
            install(ContentNegotiation) { json(safeBrowsingJson) }
        }
    }

    single(named(EXPANDER_HTTP_CLIENT)) {
        HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
        }
    }

    single { ReputationCache() }

    single<ReputationProvider> {
        SafeBrowsingReputationProvider(
            client = SafeBrowsingClient(get(named(REPUTATION_HTTP_CLIENT)), apiKey = SAFE_BROWSING_API_KEY),
            cache = get(),
        )
    }

    single<UrlExpander> { HttpUrlExpander(get(named(EXPANDER_HTTP_CLIENT))) }

    single {
        val context: Context = get()
        val driver = AndroidSqliteDriver(UrlInspectorDatabase.Schema, context, "url_inspector.db")
        UrlInspectorDatabase(driver)
    }

    single<ScanRepository> { SqlDelightScanRepository(get()) }

    single { ScanUrlUseCase(get(), get(), get()) }
}
```

- [ ] **Step 2: Wire Koin startup into `UrlInspectorApp`**

`androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt`:

```kotlin
package com.urlinspector.app

import android.app.Application
import com.urlinspector.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class UrlInspectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@UrlInspectorApp)
            modules(appModule)
        }
    }
}
```

- [ ] **Step 3: Verify the module builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (No automated DI-graph test in this task —
per M3 Global Constraints, `AndroidSqliteDriver` needs a real Android
`Context`, so a plain JVM Koin `checkModules()` test isn't practical here;
the real proof this graph resolves correctly is the app actually
launching without crashing in Task 25.)

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt
git commit -m "feat(androidApp): wire Koin DI module for core/data dependencies"
```

---

### Task 20: `ScanViewModel`

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/scan/ScanViewModel.kt`
- Test: `androidApp/src/test/kotlin/com/urlinspector/app/scan/ScanViewModelTest.kt`
- Test fixture: `androidApp/src/test/kotlin/com/urlinspector/app/fakes/CoreFakes.kt`

**Interfaces:**
- Consumes: `core.ScanUrlUseCase`, `core.model.ScanResult` (from
  `:core`). The test needs `core.ReputationProvider`/`core.UrlExpander`/
  `core.ScanRepository` fakes to construct a real `ScanUrlUseCase` — these
  live in `core`'s own **test** source set (not visible from
  `androidApp`'s tests), so this task defines small local equivalents
  instead of trying to share them across modules.
- Produces: `sealed interface ScanUiState` (`Idle`, `Loading`,
  `Success(result)`, `Error(message)`) and
  `class ScanViewModel(scanUrlUseCase: ScanUrlUseCase) : ViewModel()` with
  `val uiState: StateFlow<ScanUiState>`, `fun scan(rawUrl: String)`, and
  `fun reset()`. Task 21 (`PasteScreen`) and Task 24 (navigation) consume
  this directly.

- [ ] **Step 1: Write local test fakes for `:core`'s interfaces**

`androidApp/src/test/kotlin/com/urlinspector/app/fakes/CoreFakes.kt`:

```kotlin
package com.urlinspector.app.fakes

import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.UrlExpander
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.ScannedUrl

class FakeReputationProvider(
    override val id: String = "fake-reputation",
) : ReputationProvider {
    override suspend fun check(url: ScannedUrl): ReputationResult =
        ReputationResult(matched = false, source = id, checked = true)
}

class FakeUrlExpander : UrlExpander {
    override suspend fun expand(url: ScannedUrl): ScannedUrl = url
}

class FakeScanRepository : ScanRepository {
    val saved = mutableListOf<ScanHistoryEntry>()

    override suspend fun save(entry: ScanHistoryEntry) {
        saved.add(entry)
    }

    override suspend fun getAll(): List<ScanHistoryEntry> = saved.toList()

    override suspend fun deleteById(id: String) {
        saved.removeAll { it.id == id }
    }

    override suspend fun clearAll() {
        saved.clear()
    }
}
```

- [ ] **Step 2: Write the failing test**

`androidApp/src/test/kotlin/com/urlinspector/app/scan/ScanViewModelTest.kt`:

```kotlin
package com.urlinspector.app.scan

import com.urlinspector.app.fakes.FakeReputationProvider
import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.app.fakes.FakeUrlExpander
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts in Idle state`() {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        assertTrue(viewModel.uiState.value is ScanUiState.Idle)
    }

    @Test
    fun `scanning a valid URL transitions through Loading to Success`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("http://example.com")
        assertTrue(viewModel.uiState.value is ScanUiState.Loading)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ScanUiState.Success)
        assertEquals(Verdict.SAFE, (state as ScanUiState.Success).result.verdict)
    }

    @Test
    fun `scanning an invalid URL transitions to Error`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ScanUiState.Error)
    }

    @Test
    fun `reset returns to Idle`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("http://example.com")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()

        assertTrue(viewModel.uiState.value is ScanUiState.Idle)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.scan.ScanViewModelTest"`
Expected: `BUILD FAILED` — `ScanViewModel`/`ScanUiState` are unresolved
references.

- [ ] **Step 4: Write minimal implementation**

`androidApp/src/main/kotlin/com/urlinspector/app/scan/ScanViewModel.kt`:

```kotlin
package com.urlinspector.app.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Loading : ScanUiState
    data class Success(val result: ScanResult) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(
    private val scanUrlUseCase: ScanUrlUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun scan(rawUrl: String) {
        _uiState.value = ScanUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                ScanUiState.Success(scanUrlUseCase.scan(rawUrl))
            } catch (e: Exception) {
                ScanUiState.Error(e.message ?: "Invalid URL")
            }
        }
    }

    fun reset() {
        _uiState.value = ScanUiState.Idle
    }
}
```

You will also need `testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")` and
`testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.4")` added to
`androidApp/build.gradle.kts`'s dependencies if `@BeforeEach`/`@AfterEach`
(JUnit 5 Jupiter annotations) aren't already resolvable via
`kotlin("test-junit5")` alone — check whether the test compiles first
before adding these; `kotlin("test-junit5")` typically pulls in the
Jupiter API transitively, but confirm with a real build rather than
assuming.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.scan.ScanViewModelTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/scan/ScanViewModel.kt androidApp/src/test/kotlin/com/urlinspector/app/scan/ScanViewModelTest.kt androidApp/src/test/kotlin/com/urlinspector/app/fakes/CoreFakes.kt
git commit -m "feat(androidApp): add ScanViewModel with Idle/Loading/Success/Error states"
```

---

### Task 21: `PasteScreen` composable

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt`

**Interfaces:**
- Consumes: `ScanUiState` (Task 20).
- Produces: `@Composable fun PasteScreen(uiState: ScanUiState, onScan: (String) -> Unit, onOpenHistory: () -> Unit, modifier: Modifier = Modifier)`.
  Task 24 (navigation) wires this into the nav graph.

- [ ] **Step 1: Create the file**

`androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt`:

```kotlin
package com.urlinspector.app.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PasteScreen(
    uiState: ScanUiState,
    onScan: (String) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var urlText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("URL Inspector", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Paste a link to check") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = { onScan(urlText) },
            enabled = urlText.isNotBlank() && uiState !is ScanUiState.Loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState is ScanUiState.Loading) "Scanning…" else "Scan")
        }

        if (uiState is ScanUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(onClick = onOpenHistory) {
            Text("View scan history")
        }
    }
}
```

- [ ] **Step 2: Verify the module builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (No automated test for this Composable in
this M3 pass — per M3 Global Constraints, real verification happens on
the emulator in Task 25.)

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt
git commit -m "feat(androidApp): add PasteScreen composable"
```

---

### Task 22: `VerdictScreen` composable

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/scan/VerdictScreen.kt`

**Interfaces:**
- Consumes: `core.model.ScanResult`, `core.model.Verdict` (from `:core`).
- Produces: `@Composable fun VerdictScreen(result: ScanResult, onBack: () -> Unit, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier)`.
  Task 24 (navigation) wires this into the nav graph.

- [ ] **Step 1: Create the file**

`androidApp/src/main/kotlin/com/urlinspector/app/scan/VerdictScreen.kt`:

```kotlin
package com.urlinspector.app.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.urlinspector.core.model.ScanResult
import com.urlinspector.core.model.Verdict

@Composable
fun VerdictScreen(
    result: ScanResult,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (label, color) = when (result.verdict) {
            Verdict.SAFE -> "Safe" to MaterialTheme.colorScheme.primary
            Verdict.SUSPICIOUS -> "Suspicious" to MaterialTheme.colorScheme.tertiary
            Verdict.MALICIOUS -> "Malicious" to MaterialTheme.colorScheme.error
        }

        Text(label, style = MaterialTheme.typography.headlineLarge, color = color)
        Text(result.finalUrl.normalized, style = MaterialTheme.typography.bodyMedium)

        if (!result.reputationResult.checked) {
            Text("Reputation check could not be completed — showing on-device checks only.")
        }

        if (result.heuristicFindings.isEmpty()) {
            Text("No issues found.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(result.heuristicFindings) { finding ->
                    Text("• ${finding.description}", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }

        Button(onClick = { onOpenLink(result.finalUrl.normalized) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open link anyway")
        }

        TextButton(onClick = onBack) {
            Text("Scan another link")
        }
    }
}
```

- [ ] **Step 2: Verify the module builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/scan/VerdictScreen.kt
git commit -m "feat(androidApp): add VerdictScreen composable"
```

---

### Task 23: History feature — `HistoryViewModel` + `HistoryScreen`

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryViewModel.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryScreen.kt`
- Test: `androidApp/src/test/kotlin/com/urlinspector/app/history/HistoryViewModelTest.kt`

**Interfaces:**
- Consumes: `core.ScanRepository`, `core.model.ScanHistoryEntry`,
  `core.model.Verdict` (from `:core`); the `FakeScanRepository` test
  fixture from Task 20 (`androidApp`'s own test source set).
- Produces: `class HistoryViewModel(scanRepository: ScanRepository) : ViewModel()`
  with `val entries: StateFlow<List<ScanHistoryEntry>>`,
  `fun refresh()`, `fun delete(id: String)`, `fun clearAll()`; and
  `@Composable fun HistoryScreen(entries: List<ScanHistoryEntry>, onDelete: (String) -> Unit, onClearAll: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)`.
  Task 24 (navigation) wires both into the nav graph.

- [ ] **Step 1: Write the failing test**

`androidApp/src/test/kotlin/com/urlinspector/app/history/HistoryViewModelTest.kt`:

```kotlin
package com.urlinspector.app.history

import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads existing entries on init`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.entries.value.size)
    }

    @Test
    fun `delete removes an entry and refreshes`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.delete("1")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.entries.value.isEmpty())
    }

    @Test
    fun `clearAll empties the list`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        repository.save(ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.clearAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.entries.value.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.history.HistoryViewModelTest"`
Expected: `BUILD FAILED` — `HistoryViewModel` is an unresolved reference.

- [ ] **Step 3: Write minimal implementation**

`androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryViewModel.kt`:

```kotlin
package com.urlinspector.app.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val scanRepository: ScanRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<ScanHistoryEntry>>(emptyList())
    val entries: StateFlow<List<ScanHistoryEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = scanRepository.getAll()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            scanRepository.deleteById(id)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            scanRepository.clearAll()
            refresh()
        }
    }
}
```

`androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryScreen.kt`:

```kotlin
package com.urlinspector.app.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.urlinspector.core.model.ScanHistoryEntry

@Composable
fun HistoryScreen(
    entries: List<ScanHistoryEntry>,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Scan History", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onClearAll, enabled = entries.isNotEmpty()) {
                Text("Clear all")
            }
        }

        if (entries.isEmpty()) {
            Text("No scans yet.")
        } else {
            LazyColumn {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(entry.url, style = MaterialTheme.typography.bodyMedium)
                            Text(entry.verdict.name, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onDelete(entry.id) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.history.HistoryViewModelTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Verify the whole module still builds (Compose file included)**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryViewModel.kt androidApp/src/main/kotlin/com/urlinspector/app/history/HistoryScreen.kt androidApp/src/test/kotlin/com/urlinspector/app/history/HistoryViewModelTest.kt
git commit -m "feat(androidApp): add HistoryViewModel and HistoryScreen"
```

---

### Task 24: Navigation wiring — `AppNavHost` + `MainActivity`

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt`

**Interfaces:**
- Consumes: `ScanViewModel`/`ScanUiState` (Task 20), `PasteScreen` (Task
  21), `VerdictScreen` (Task 22), `HistoryViewModel`/`HistoryScreen`
  (Task 23) — all already built and committed.
- Produces: `@Composable fun AppNavHost(onOpenLink: (String) -> Unit, navController: NavHostController = rememberNavController(), scanViewModel: ScanViewModel = koinViewModel())`,
  wired into `MainActivity.onCreate`. This is the last piece connecting
  every screen — after this task the app is feature-complete for M3
  pending on-device verification (Task 25).

- [ ] **Step 1: Create `androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt`**

```kotlin
package com.urlinspector.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.urlinspector.app.history.HistoryScreen
import com.urlinspector.app.history.HistoryViewModel
import com.urlinspector.app.scan.PasteScreen
import com.urlinspector.app.scan.ScanUiState
import com.urlinspector.app.scan.ScanViewModel
import com.urlinspector.app.scan.VerdictScreen
import org.koin.androidx.compose.koinViewModel

private const val ROUTE_PASTE = "paste"
private const val ROUTE_VERDICT = "verdict"
private const val ROUTE_HISTORY = "history"

@Composable
fun AppNavHost(
    onOpenLink: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
    scanViewModel: ScanViewModel = koinViewModel(),
) {
    val uiState by scanViewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = ROUTE_PASTE) {
        composable(ROUTE_PASTE) {
            PasteScreen(
                uiState = uiState,
                onScan = { url -> scanViewModel.scan(url) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
            )
            LaunchedEffect(uiState) {
                if (uiState is ScanUiState.Success) {
                    navController.navigate(ROUTE_VERDICT)
                }
            }
        }
        composable(ROUTE_VERDICT) {
            val state = uiState
            if (state is ScanUiState.Success) {
                VerdictScreen(
                    result = state.result,
                    onBack = {
                        scanViewModel.reset()
                        navController.popBackStack(ROUTE_PASTE, inclusive = false)
                    },
                    onOpenLink = onOpenLink,
                )
            }
        }
        composable(ROUTE_HISTORY) {
            val historyViewModel: HistoryViewModel = koinViewModel()
            val entries by historyViewModel.entries.collectAsState()
            HistoryScreen(
                entries = entries,
                onDelete = historyViewModel::delete,
                onClearAll = historyViewModel::clearAll,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
```

- [ ] **Step 2: Wire it into `MainActivity`**

Replace the entire contents of
`androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt` with:

```kotlin
package com.urlinspector.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(onOpenLink = ::openLink)
                }
            }
        }
    }

    private fun openLink(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
```

- [ ] **Step 3: Verify the module builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full unit test suite across all modules**

Run: `./gradlew test testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` — `:core:test` (38), `:data:test` (18),
`:androidApp:testDebugUnitTest` (7: 4 `ScanViewModelTest` + 3
`HistoryViewModelTest`) all green.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt
git commit -m "feat(androidApp): wire navigation between paste, verdict, and history screens"
```

---

### Task 25: On-device/emulator verification

**Files:** none created — this task drives the already-built app on the
running emulator to prove the M3 exit criteria are actually met, not
just that the code compiles.

**Interfaces:**
- Consumes: the fully-assembled `androidApp-debug.apk` from Tasks 18-24.
- Produces: a verification report — no new source files.

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Confirm the emulator is running**

Run: `adb devices`
Expected: `emulator-5554` listed with state `device`. If not running,
start it: `emulator -avd url_inspector_avd -no-window -no-audio -no-boot-anim &`
then `adb wait-for-device` and poll
`adb shell getprop sys.boot_completed` until it prints `1`.

- [ ] **Step 3: Install and launch the app**

Run: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
Then: `adb shell am start -n com.urlinspector.app/.MainActivity`
Expected: no crash. Confirm with
`adb logcat -d -s AndroidRuntime:E *:S` showing no fatal exception
stack trace from `com.urlinspector.app` since the launch.

- [ ] **Step 4: Drive the manual-paste flow**

Use `adb shell input tap <x> <y>` / `adb shell input text <url>` (take a
screenshot first via `adb exec-out screencap -p > /tmp/screen1.png` and
pull it locally to find real coordinates for the text field and Scan
button on this device's resolution — do not guess coordinates blind).
Sequence to verify:
1. Tap the URL text field, type `http://paypa1.com/login` (a typosquat
   example — no network dependency needed for this one, since the
   heuristic fires regardless of the reputation lookup's outcome).
2. Tap "Scan".
3. Screenshot again — confirm the Verdict screen appears showing
   "Suspicious" and a reason mentioning `paypal.com`.
4. Tap "Scan another link", confirm it returns to the paste screen.
5. Tap "View scan history", screenshot — confirm the just-completed scan
   appears in the list.
6. Tap "Delete" on that entry, screenshot — confirm the list is now
   empty (or "No scans yet." shows).

- [ ] **Step 5: Check logcat for the expected graceful reputation fallback**

Run: `adb logcat -d | grep -i "url-inspector\|ktor\|HttpTimeout" | tail -50`
This is informational, not a hard pass/fail gate — with the empty API
key (M3 Global Constraints), the reputation lookup is expected to fail
per-request (a 400/403 from Safe Browsing, or a timeout if there's no
network path from the emulator), and the verdict screen should still
render correctly using heuristics alone. If the app crashes instead of
falling back gracefully, that's a real regression to investigate, not
expected behavior.

- [ ] **Step 6: Write up the verification result**

No commit needed for this task (no source changes) — report the outcome
directly: which steps passed, any screenshots taken, and confirmation
that the M3 exit criteria (docs/plan.md M3 Global Constraints /
docs/intent.md FR6/FR7) are met on a real running emulator, not just in
unit tests.

---

## M4 Global Constraints

These bind Tasks 26-29 below.

- **No new detection logic**: M4 is pure platform/UI plumbing on top of
  M1-M3. It must not modify `:core` or `:data` — it only adds a new
  Android entry point (`ShareHandlerActivity`) and reuses the existing
  `ScanViewModel` / `ScanUrlUseCase` / `VerdictScreen` / `PasteScreen`
  exactly as M3 built them.
- **Base package / toolchain**: same as M3 — `com.urlinspector.app`,
  `minSdk = 26`, `compileSdk = 37`, `targetSdk = 35`, Kotlin/Java 17,
  same dependency versions already declared in `androidApp/build.gradle.kts`
  (no new dependencies are needed for M4).
- **Share-text extraction is heuristic, not validation**: incoming
  `EXTRA_TEXT` from a real share (WhatsApp, Messages, Chrome, etc.) is
  arbitrary human/app-generated text, e.g. `"Check this out:
  https://example.com/x"` or a bare URL. Extraction finds the first
  `http`/`https` substring and trims common trailing punctuation
  (`.` `,` `)` `]` `}` `!` `?` `;` `:` `'` `"`). It does **not** validate
  the URL — that job belongs entirely to `core.UrlNormalizer` /
  `ScanUrlUseCase`, which M4 must not duplicate or bypass.
- **No detected URL fallback**: if no `http`/`https` substring is found
  in the shared text, `ShareHandlerActivity` must not crash, silently
  drop the share, or auto-scan garbage — it opens the paste screen with
  the raw shared text pre-filled into the input field so the user can
  fix/complete it manually.
- **Scan triggers at most once per share**: auto-scanning must not
  re-fire on recomposition or configuration change (e.g. rotation) —
  gate it on `ScanUiState` being `Idle`, the same guard pattern already
  used for `Loading`/`Success` transitions elsewhere in `AppNavHost`.
- **Out of scope for M4** (per docs/plan.md's own prior scope notes):
  SMS scanning (M5), release/signing config (M6), and — continuing M3's
  explicit scope-narrowing decision — Compose UI instrumented
  (`androidTest`) tests. Verification is via (a) plain JVM unit tests
  for the one piece of new branching logic (URL extraction) and (b) a
  real on-device share-intent run in Task 29, same pattern as M3's
  Task 25.

---

## M4 — Share-sheet Integration: Detailed Tasks

### Task 26: Share-text URL extraction utility

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/share/ShareTextUrlExtractor.kt`
- Test: `androidApp/src/test/kotlin/com/urlinspector/app/share/ShareTextUrlExtractorTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin, no Android/core/data dependencies).
- Produces: `fun extractFirstUrl(text: String): String?` — used by Task 28's
  `ShareHandlerActivity`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.urlinspector.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareTextUrlExtractorTest {

    @Test
    fun `extracts a bare URL`() {
        assertEquals("https://example.com", extractFirstUrl("https://example.com"))
    }

    @Test
    fun `extracts a URL with leading surrounding text`() {
        assertEquals(
            "https://example.com/x",
            extractFirstUrl("Check this out: https://example.com/x"),
        )
    }

    @Test
    fun `extracts a URL with trailing surrounding text`() {
        assertEquals(
            "http://example.com/page",
            extractFirstUrl("See http://example.com/page for details"),
        )
    }

    @Test
    fun `trims trailing punctuation not part of the URL`() {
        assertEquals(
            "https://example.com/page",
            extractFirstUrl("Look at (https://example.com/page)."),
        )
    }

    @Test
    fun `preserves a query string`() {
        assertEquals(
            "https://example.com/page?q=1&r=2",
            extractFirstUrl("https://example.com/page?q=1&r=2 nice right?"),
        )
    }

    @Test
    fun `returns the first URL when multiple are present`() {
        assertEquals(
            "http://a.example.com",
            extractFirstUrl("http://a.example.com and also https://b.example.com"),
        )
    }

    @Test
    fun `returns null when no URL is present`() {
        assertNull(extractFirstUrl("no link in this text"))
    }

    @Test
    fun `returns null for blank text`() {
        assertNull(extractFirstUrl("   "))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.share.ShareTextUrlExtractorTest"`
Expected: FAIL with "unresolved reference: extractFirstUrl" (file doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlinspector.app.share

private val URL_REGEX = Regex("""https?://\S+""")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', '?', ';', ':', '\'', '"')

/**
 * Finds the first http(s) URL substring in arbitrary shared text (e.g. a
 * WhatsApp/Messages share payload like "Check this out: https://...").
 * Returns null if no URL substring is present. Does not validate the URL —
 * that is core.UrlNormalizer's job once the extracted string reaches
 * ScanUrlUseCase.
 */
fun extractFirstUrl(text: String): String? {
    val match = URL_REGEX.find(text) ?: return null
    return match.value.trimEnd(*TRAILING_PUNCTUATION).ifBlank { null }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.share.ShareTextUrlExtractorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/share/ShareTextUrlExtractor.kt \
        androidApp/src/test/kotlin/com/urlinspector/app/share/ShareTextUrlExtractorTest.kt
git commit -m "feat(androidApp): add share-text URL extraction utility"
```

---

### Task 27: `AppNavHost` support for auto-scan and prefill

**Files:**
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt`

**Interfaces:**
- Consumes: `ScanViewModel.uiState`/`scan()` (Task 20, already exists),
  `PasteScreen` (Task 21, already exists).
- Produces: `AppNavHost(onOpenLink, navController, scanViewModel,
  sharedUrl: String? = null, prefillText: String? = null)` — the two new
  trailing params default to `null` so `MainActivity`'s existing call
  (`AppNavHost(onOpenLink = ::openLink)`) is unaffected. `PasteScreen`
  gains `initialText: String = ""`. Used by Task 28's
  `ShareHandlerActivity`.

This task has no new automated tests of its own (Composable
navigation/state wiring — same category M3 already established is
verified by compilation + the real on-device pass, not blind
instrumented tests). Verify via `assembleDebug` and manual reasoning
about the two new code paths; Task 29 proves both paths for real.

- [ ] **Step 1: Add `initialText` to `PasteScreen`**

In `PasteScreen.kt`, change the signature and initial state:

```kotlin
@Composable
fun PasteScreen(
    uiState: ScanUiState,
    onScan: (String) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    var urlText by rememberSaveable { mutableStateOf(initialText) }
    // ... rest of the function body is unchanged
```

- [ ] **Step 2: Wire auto-scan and prefill into `AppNavHost`**

In `AppNavHost.kt`, change the function signature and the `ROUTE_PASTE`
composable:

```kotlin
@Composable
fun AppNavHost(
    onOpenLink: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
    scanViewModel: ScanViewModel = koinViewModel(),
    sharedUrl: String? = null,
    prefillText: String? = null,
) {
    val uiState by scanViewModel.uiState.collectAsState()

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null && uiState is ScanUiState.Idle) {
            scanViewModel.scan(sharedUrl)
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_PASTE) {
        composable(ROUTE_PASTE) {
            PasteScreen(
                uiState = uiState,
                onScan = { url -> scanViewModel.scan(url) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
                initialText = prefillText ?: "",
            )
            LaunchedEffect(uiState) {
                if (uiState is ScanUiState.Success) {
                    navController.navigate(ROUTE_VERDICT)
                }
            }
        }
        // ... ROUTE_VERDICT and ROUTE_HISTORY composables unchanged
```

The `LaunchedEffect(sharedUrl)` sits above `NavHost` and is keyed on
`sharedUrl` (stable per-Activity — `ShareHandlerActivity` creates one
`AppNavHost` per share intent), so it fires once on first composition
and never again on rotation. The `uiState is ScanUiState.Idle` guard
means that even if the effect were somehow re-run, it would not
re-trigger a scan once one is in flight or complete — the same
belt-and-suspenders pattern as the existing `LaunchedEffect(uiState)`
below it.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt
git commit -m "feat(androidApp): support auto-scan and text prefill in AppNavHost"
```

---

### Task 28: `ShareHandlerActivity` and manifest registration

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/LinkOpener.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/ShareHandlerActivity.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `extractFirstUrl` (Task 26), `AppNavHost(..., sharedUrl,
  prefillText)` (Task 27).
- Produces: nothing further consumed by later tasks — this is the
  milestone's user-facing entry point. Task 29 verifies it on-device.

- [ ] **Step 1: Extract the shared link-opening helper**

Both `MainActivity` and `ShareHandlerActivity` need to open a URL in the
browser (`VerdictScreen`'s "Open link anyway"). Pull the duplicate logic
out into one file:

```kotlin
package com.urlinspector.app

import android.app.Activity
import android.content.Intent
import android.net.Uri

fun Activity.openExternalLink(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
```

- [ ] **Step 2: Update `MainActivity` to use the shared helper**

In `MainActivity.kt`, remove the private `openLink` method and its
`Intent`/`Uri` imports, and change the `AppNavHost` call:

```kotlin
AppNavHost(onOpenLink = { url -> openExternalLink(url) })
```

- [ ] **Step 3: Write `ShareHandlerActivity`**

```kotlin
package com.urlinspector.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.urlinspector.app.share.extractFirstUrl

class ShareHandlerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        val extractedUrl = sharedText?.let(::extractFirstUrl)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    AppNavHost(
                        onOpenLink = { url -> openExternalLink(url) },
                        sharedUrl = extractedUrl,
                        prefillText = if (extractedUrl == null) sharedText else null,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register the activity with a `SEND`/`text/plain` intent-filter**

In `AndroidManifest.xml`, add a second `<activity>` inside
`<application>`, alongside the existing `MainActivity` entry:

```xml
        <activity
            android:name=".ShareHandlerActivity"
            android:exported="true"
            android:label="Scan with URL Inspector"
            android:theme="@style/Theme.UrlInspector">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
```

- [ ] **Step 5: Verify it builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/LinkOpener.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/ShareHandlerActivity.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/MainActivity.kt \
        androidApp/src/main/AndroidManifest.xml
git commit -m "feat(androidApp): add ShareHandlerActivity for share-sheet integration"
```

---

### Task 29: On-device/emulator verification

**Files:** none (verification only, no source changes).

This task has no code changes and needs no `task-reviewer` code-quality
pass — it is a manual verification pass, reported the same way M3's
Task 25 was.

- [ ] **Step 1: Build and install the debug APK**

```bash
./gradlew :androidApp:installDebug
```

- [ ] **Step 2: Simulate a WhatsApp/Messages-style share with a URL present**

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "Check this out: https://example.com/test-path" \
  -n com.urlinspector.app/.ShareHandlerActivity
```

Expected: the app opens directly to the verdict screen for
`https://example.com/test-path` (no manual "Scan" tap needed). Confirm
with a screenshot (`adb exec-out screencap -p`).

- [ ] **Step 3: Simulate a share with no URL in the text**

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "just some notes, no link" \
  -n com.urlinspector.app/.ShareHandlerActivity
```

Expected: the paste screen opens with "just some notes, no link"
pre-filled in the input field, Scan button enabled, no crash. Confirm
with a screenshot.

- [ ] **Step 4: Confirm the share-triggered scan is recorded in history**

From the verdict screen reached in Step 2, navigate back to Paste, then
"View scan history" — confirm the `example.com/test-path` entry is
present (reusing the same `ScanRepository` M3 already wired). Confirm
with a screenshot.

- [ ] **Step 5: Confirm `MainActivity`'s own flow is unaffected**

Launch normally (`adb shell am start -n
com.urlinspector.app/.MainActivity`), paste a URL, scan it, and confirm
"Open link anyway" still opens the browser — proves the `LinkOpener`
extraction in Task 28 didn't regress the existing path.

No commit needed for this task (no source changes) — report the outcome
directly: which steps passed, any screenshots taken, and confirmation
that the M4 exit criteria (docs/plan.md's M4 goal / docs/spec.md §8)
are met on a real running emulator, not just in unit tests.

---

## M5 Global Constraints

These bind Tasks 30-36 below.

- **Design decisions locked in for M5** (per spec.md §8's "decide during
  implementation" note):
  - Observation mechanism: `READ_SMS` permission + a `ContentObserver` on
    `Telephony.Sms.CONTENT_URI` — **not** `RECEIVE_SMS`/a
    `BroadcastReceiver`, and **not** becoming the default SMS app (that
    would require implementing a full SMS compose/send/MMS stack, wildly
    out of scope for an opt-in security feature). This matches spec.md
    §8's explicit wording ("register a `ContentObserver` on
    `content://sms`").
  - Persistence mechanism: a **foreground `Service`**, not `WorkManager`.
    `WorkManager`'s minimum periodic interval (15 minutes) cannot deliver
    "when a new inbound message arrives" in near-real-time, which is the
    whole point of the feature. A foreground service with a visible,
    low-priority ongoing notification is the standard, honest pattern for
    "this app is actively watching something in the background" on modern
    Android, and is what keeps the process (and the `ContentObserver`
    registration) alive against Doze/background-execution limits.
  - Scope boundary: **no boot-persistence** (`RECEIVE_BOOT_COMPLETED` /
    auto-restart after device reboot) — the service starts only when the
    user turns the Settings toggle on in this app session, and that is a
    deliberate scope-narrowing decision, not an oversight. Revisit if a
    future milestone needs "always on across reboots."
- **Permissions this milestone adds** (all requested only when the user
  opts in, never at first launch, per intent.md §6 and spec.md §12):
  `READ_SMS` (runtime, dangerous), `POST_NOTIFICATIONS` (runtime,
  API 33+ only — required to show the per-message scan-result
  notifications), `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_SPECIAL_USE` (normal, manifest-only, needed because
  `targetSdk = 35` requires every foreground service to declare a
  `android:foregroundServiceType`, and "observing an SMS content
  provider" doesn't map to any of the standard typed categories like
  `dataSync`/`location`/`mediaPlayback` — `specialUse` is the documented
  catch-all for exactly this case, and it requires a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest `<property>` describing the
  use case in the `<service>` block).
- **Reuses from M1-M4, do not reimplement**: `core.ScanUrlUseCase.scan()`
  (already normalizes, scans, and saves to history — SMS-detected URLs
  go through the exact same pipeline as manual paste and share-sheet
  URLs); `androidApp.share.extractFirstUrl`/`extractUrls` (Task 30
  generalizes the existing M4 extractor rather than duplicating regex
  logic); `ShareHandlerActivity` (Task 33's notification tap reuses it
  by constructing a synthetic `ACTION_SEND`/`EXTRA_TEXT` intent — the
  exact same entry point WhatsApp/Messages already use, so tapping a
  notification gets the identical auto-scan-to-verdict behavior M4 built
  and already fixed the rotation/duplicate-scan bug for).
- **Privacy (intent.md §7, spec.md §12, binding)**: only extracted URLs
  are ever sent off-device (to the reputation provider) or persisted (as
  `ScanHistoryEntry.url`); full SMS message bodies are read into memory
  transiently to extract URLs and are never logged, persisted, or
  transmitted. Do not add any logging statement that prints a message
  body.
- **Verdict on "revoking permission stops scanning"**: the plan's own M5
  summary requires this to be verified. Two mechanisms cover it: (a) the
  Settings screen re-checks `READ_SMS` on every resume and force-disables
  the toggle (stopping the service) if the OS-level permission no longer
  matches the persisted "enabled" state; (b) the service's own
  `ContentObserver` query is wrapped in a `try/catch (SecurityException)`
  that calls `stopSelf()` if a query fails after revocation, so scanning
  stops immediately rather than waiting for the user to reopen Settings.
- Dependency versions: no new dependencies are required — `androidx.core`
  (`NotificationCompat`/`NotificationManagerCompat`, already pulled in via
  `androidx.core:core-ktx:1.19.0`) and `androidx.activity`
  (`ActivityResultContracts`, already pulled in via
  `androidx.activity:activity-compose:1.13.0`) already cover everything
  this milestone needs.
- Out of scope for M5: RCS-specific handling (RCS messages that arrive
  via Google Messages' RCS transport are not exposed through
  `content://sms` — this milestone covers SMS only, matching what's
  actually implementable via the public `Telephony.Sms` provider),
  release/signing config (M6), boot-persistence (see above).

---

## M5 — Opt-in SMS Scanning: Detailed Tasks

### Task 30: Generalize the URL extractor to `extractUrls` (multi-URL)

**Files:**
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/share/ShareTextUrlExtractor.kt`
- Modify: `androidApp/src/test/kotlin/com/urlinspector/app/share/ShareTextUrlExtractorTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun extractUrls(text: String): List<String>` (new). Existing
  `fun extractFirstUrl(text: String): String?` (from M4, used by
  `ShareHandlerActivity`) keeps its exact signature and behavior, now
  implemented in terms of `extractUrls`. Task 32 consumes `extractUrls`.

- [ ] **Step 1: Write the failing test for `extractUrls`**

Add these test cases to the existing `ShareTextUrlExtractorTest` class
(keep all existing tests for `extractFirstUrl` — they must still pass
unmodified):

```kotlin
    @Test
    fun `extractUrls finds all URLs in text`() {
        assertEquals(
            listOf("http://a.example.com", "https://b.example.com"),
            extractUrls("http://a.example.com and also https://b.example.com"),
        )
    }

    @Test
    fun `extractUrls returns a single-element list for one URL`() {
        assertEquals(
            listOf("https://example.com/page"),
            extractUrls("Look at (https://example.com/page)."),
        )
    }

    @Test
    fun `extractUrls returns empty list when no URL is present`() {
        assertEquals(emptyList(), extractUrls("no link in this text"))
    }

    @Test
    fun `extractUrls returns empty list for blank text`() {
        assertEquals(emptyList(), extractUrls("   "))
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.share.ShareTextUrlExtractorTest"`
Expected: the 4 new tests FAIL with "unresolved reference: extractUrls";
all pre-existing tests still pass.

- [ ] **Step 3: Implement `extractUrls` and reimplement `extractFirstUrl` on top of it**

Replace the full contents of `ShareTextUrlExtractor.kt` with:

```kotlin
package com.urlinspector.app.share

private val URL_REGEX = Regex("""https?://\S+""")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '!', '?', ';', ':', '\'', '"')

/**
 * Finds every http(s) URL substring in arbitrary text (a shared payload,
 * or an SMS message body). Does not validate the URLs — that is
 * core.UrlNormalizer's job once a candidate reaches ScanUrlUseCase.
 */
fun extractUrls(text: String): List<String> =
    URL_REGEX.findAll(text)
        .map { it.value.trimEnd(*TRAILING_PUNCTUATION) }
        .toList()

/**
 * Finds the first http(s) URL substring in arbitrary shared text (e.g. a
 * WhatsApp/Messages share payload like "Check this out: https://...").
 * Returns null if no URL substring is present.
 */
fun extractFirstUrl(text: String): String? = extractUrls(text).firstOrNull()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.share.ShareTextUrlExtractorTest"`
Expected: PASS (12 tests total — 8 pre-existing `extractFirstUrl` tests +
4 new `extractUrls` tests).

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/share/ShareTextUrlExtractor.kt \
        androidApp/src/test/kotlin/com/urlinspector/app/share/ShareTextUrlExtractorTest.kt
git commit -m "feat(androidApp): generalize URL extraction to support multiple URLs"
```

---

### Task 31: `ScanPreferences` — SMS-scanning opt-in flag storage

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/settings/ScanPreferences.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt`

**Interfaces:**
- Consumes: Android `Context` (for `SharedPreferences`), already
  available in Koin's DI graph (see `AppModule.kt`'s existing
  `single { val context: Context = get(); ... }` pattern for the
  database).
- Produces: `interface ScanPreferences { val smsScanningEnabled:
  StateFlow<Boolean>; fun setSmsScanningEnabled(enabled: Boolean) }` and
  its real implementation `SharedPreferencesScanPreferences`, bound as a
  Koin `single<ScanPreferences>`. Consumed by Task 35's
  `SettingsViewModel`.

This task has no automated test of its own — it's a thin
`SharedPreferences` wrapper (Android framework glue), following the same
established precedent as M3/M4's Activity/Manifest tasks: verified by
compilation and a later on-device task (Task 36), not a unit test that
would just be re-testing `SharedPreferences` itself.

- [ ] **Step 1: Write `ScanPreferences.kt`**

```kotlin
package com.urlinspector.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "url_inspector_settings"
private const val KEY_SMS_SCANNING_ENABLED = "sms_scanning_enabled"

interface ScanPreferences {
    val smsScanningEnabled: StateFlow<Boolean>
    fun setSmsScanningEnabled(enabled: Boolean)
}

class SharedPreferencesScanPreferences(context: Context) : ScanPreferences {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _smsScanningEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SMS_SCANNING_ENABLED, false),
    )
    override val smsScanningEnabled: StateFlow<Boolean> = _smsScanningEnabled.asStateFlow()

    override fun setSmsScanningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_SCANNING_ENABLED, enabled).apply()
        _smsScanningEnabled.value = enabled
    }
}
```

Note the default is `false` (off), satisfying FR3's "off by default"
requirement — `getBoolean(KEY_SMS_SCANNING_ENABLED, false)` is the single
source of truth for that default.

- [ ] **Step 2: Wire it into `AppModule.kt`**

Add this Koin binding (place it near the other `single { ... }`
declarations, after the database binding is a reasonable spot):

```kotlin
    single<ScanPreferences> {
        val context: Context = get()
        SharedPreferencesScanPreferences(context)
    }
```

Add the import: `import com.urlinspector.app.settings.ScanPreferences`
and `import com.urlinspector.app.settings.SharedPreferencesScanPreferences`.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/settings/ScanPreferences.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt
git commit -m "feat(androidApp): add ScanPreferences for SMS-scanning opt-in flag"
```

---

### Task 32: `SmsScanCoordinator` — the testable core of SMS scanning

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/sms/ScanNotifier.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/sms/SmsScanCoordinator.kt`
- Test: `androidApp/src/test/kotlin/com/urlinspector/app/sms/SmsScanCoordinatorTest.kt`

**Interfaces:**
- Consumes: `core.ScanUrlUseCase.scan(rawUrl: String): ScanResult` (throws
  `core.InvalidUrlException` on an unparseable URL — already exists),
  `androidApp.share.extractUrls(text: String): List<String>` (Task 30).
- Produces: `interface ScanNotifier { fun notify(url: String, verdict:
  Verdict) }` and `class SmsScanCoordinator(scanUrlUseCase: ScanUrlUseCase,
  notifier: ScanNotifier) { suspend fun processMessageBody(body: String) }`.
  Task 33 provides the real `ScanNotifier` implementation
  (`AndroidScanNotifier`). Task 34's `SmsContentObserverService` is
  `SmsScanCoordinator`'s only caller.

This is the one piece of real branching logic in this milestone's SMS
pipeline — it gets full unit test coverage with fakes, the same pattern
`ScanViewModelTest` established in M3.

- [ ] **Step 1: Write `ScanNotifier.kt`**

```kotlin
package com.urlinspector.app.sms

import com.urlinspector.core.model.Verdict

interface ScanNotifier {
    fun notify(url: String, verdict: Verdict)
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.urlinspector.app.sms

import com.urlinspector.app.fakes.FakeReputationProvider
import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.app.fakes.FakeUrlExpander
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeScanNotifier : ScanNotifier {
    data class Notification(val url: String, val verdict: Verdict)
    val notifications = mutableListOf<Notification>()

    override fun notify(url: String, verdict: Verdict) {
        notifications.add(Notification(url, verdict))
    }
}

class SmsScanCoordinatorTest {

    private fun coordinator(notifier: FakeScanNotifier): SmsScanCoordinator {
        val scanUrlUseCase = ScanUrlUseCase(
            reputationProvider = FakeReputationProvider(),
            urlExpander = FakeUrlExpander(),
            scanRepository = FakeScanRepository(),
        )
        return SmsScanCoordinator(scanUrlUseCase, notifier)
    }

    @Test
    fun `scans and notifies for a single URL in the message`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("Your package: https://example.com/track")

        assertEquals(1, notifier.notifications.size)
        assertEquals("https://example.com/track", notifier.notifications[0].url)
        assertEquals(Verdict.SAFE, notifier.notifications[0].verdict)
    }

    @Test
    fun `scans and notifies for each URL when multiple are present`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("https://a.example.com and https://b.example.com")

        assertEquals(2, notifier.notifications.size)
    }

    @Test
    fun `does nothing for a message with no URL`() = runTest {
        val notifier = FakeScanNotifier()
        coordinator(notifier).processMessageBody("Your OTP is 123456")

        assertEquals(0, notifier.notifications.size)
    }

    @Test
    fun `does not crash and does not notify when the extracted candidate is not a valid URL`() = runTest {
        val notifier = FakeScanNotifier()
        // "https://" extracts as a URL-shaped substring but has no host,
        // so core.UrlNormalizer rejects it — ScanUrlUseCase throws
        // InvalidUrlException, which the coordinator must swallow.
        coordinator(notifier).processMessageBody("weird link: https://")

        assertEquals(0, notifier.notifications.size)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.sms.SmsScanCoordinatorTest"`
Expected: FAIL with "unresolved reference: SmsScanCoordinator".

- [ ] **Step 4: Write `SmsScanCoordinator.kt`**

```kotlin
package com.urlinspector.app.sms

import com.urlinspector.app.share.extractUrls
import com.urlinspector.core.InvalidUrlException
import com.urlinspector.core.ScanUrlUseCase
import kotlinx.coroutines.CancellationException

class SmsScanCoordinator(
    private val scanUrlUseCase: ScanUrlUseCase,
    private val notifier: ScanNotifier,
) {
    suspend fun processMessageBody(body: String) {
        for (url in extractUrls(body)) {
            try {
                val result = scanUrlUseCase.scan(url)
                notifier.notify(result.finalUrl.normalized, result.verdict)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidUrlException) {
                // The extracted candidate looked URL-shaped but wasn't
                // actually valid (e.g. no host) — skip it silently rather
                // than notifying about garbage.
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.sms.SmsScanCoordinatorTest"`
Expected: PASS (4/4).

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/sms/ScanNotifier.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/sms/SmsScanCoordinator.kt \
        androidApp/src/test/kotlin/com/urlinspector/app/sms/SmsScanCoordinatorTest.kt
git commit -m "feat(androidApp): add SmsScanCoordinator for SMS URL scan+notify pipeline"
```

---

### Task 33: Notification channels + `AndroidScanNotifier`

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/sms/NotificationChannels.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/sms/AndroidScanNotifier.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt`

**Interfaces:**
- Consumes: `ScanNotifier` (Task 32), `ShareHandlerActivity` (M4, reused
  as the notification-tap target).
- Produces: `object NotificationChannels` with `CHANNEL_ID_STATUS` /
  `CHANNEL_ID_RESULTS` constants and a `fun ensureCreated(context:
  Context)`; `class AndroidScanNotifier(context: Context) : ScanNotifier`.
  Task 34's `SmsContentObserverService` uses both.

No automated test — this is Android notification/framework glue,
verified by compilation and Task 36's on-device pass (a real posted
notification is the only meaningful proof this works).

- [ ] **Step 1: Write `NotificationChannels.kt`**

```kotlin
package com.urlinspector.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

object NotificationChannels {
    const val CHANNEL_ID_STATUS = "sms_scan_status"
    const val CHANNEL_ID_RESULTS = "sms_scan_results"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager: NotificationManager = context.getSystemService() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_STATUS,
                "SMS scanning status",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Shows while URL Inspector is watching incoming SMS messages for links."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_RESULTS,
                "SMS link scan results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A link was found in an SMS message and scanned."
            },
        )
    }
}
```

- [ ] **Step 2: Call it from `UrlInspectorApp.onCreate()`**

Modify `UrlInspectorApp.kt` — add the call after `startKoin { ... }`:

```kotlin
package com.urlinspector.app

import android.app.Application
import com.urlinspector.app.di.appModule
import com.urlinspector.app.sms.NotificationChannels
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class UrlInspectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@UrlInspectorApp)
            modules(appModule)
        }
        NotificationChannels.ensureCreated(this)
    }
}
```

Channels must exist before any notification (including the foreground
service's own status notification, Task 34) is posted — creating them
unconditionally at app startup (a no-op if they already exist — channel
creation is idempotent) is simpler and safer than creating them lazily
at first use.

- [ ] **Step 3: Write `AndroidScanNotifier.kt`**

```kotlin
package com.urlinspector.app.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.urlinspector.app.ShareHandlerActivity
import com.urlinspector.core.model.Verdict

class AndroidScanNotifier(private val context: Context) : ScanNotifier {

    override fun notify(url: String, verdict: Verdict) {
        val title = when (verdict) {
            Verdict.SAFE -> "Safe link found in a text message"
            Verdict.SUSPICIOUS -> "Suspicious link found in a text message"
            Verdict.MALICIOUS -> "⚠️ Malicious link found in a text message"
        }

        // Reuses ShareHandlerActivity's existing ACTION_SEND/EXTRA_TEXT
        // handling (M4) — the exact same entry point WhatsApp/Messages
        // shares use, so tapping this notification gets the identical
        // auto-scan-to-verdict flow, with no new Activity needed.
        val tapIntent = Intent(context, ShareHandlerActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            url.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ID_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(url)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // NotificationManagerCompat.notify() is a documented no-op (not a
        // crash) if POST_NOTIFICATIONS isn't granted on API 33+ — no
        // explicit permission check needed here.
        NotificationManagerCompat.from(context).notify(url.hashCode(), notification)
    }
}
```

`url.hashCode()` as the notification ID means a repeated scan of the
same URL updates/replaces its existing notification rather than piling
up duplicates — acceptable, simple behavior for v1.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/sms/NotificationChannels.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/sms/AndroidScanNotifier.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/UrlInspectorApp.kt
git commit -m "feat(androidApp): add notification channels and AndroidScanNotifier"
```

---

### Task 34: `SmsContentObserverService` — foreground service + `ContentObserver`

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/sms/SmsContentObserverService.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SmsScanCoordinator` (Task 32), `AndroidScanNotifier` (Task
  33), `core.ScanUrlUseCase` (via Koin, already bound in `AppModule.kt`).
- Produces: `class SmsContentObserverService : Service()` with
  `companion object { fun start(context: Context); fun stop(context:
  Context) }`. Task 35's Settings screen calls `start`/`stop`.

No automated test — a foreground `Service` + `ContentObserver` +
`ContentResolver` query against the real SMS provider cannot be
meaningfully unit-tested without an Android framework/Robolectric
dependency this project doesn't have; Task 36's on-device pass (using
the emulator's `adb emu sms send` to actually insert a message into the
system SMS provider) is the real, and only meaningful, proof this works
— consistent with this project's established practice for Android
framework glue.

- [ ] **Step 1: Write `SmsContentObserverService.kt`**

```kotlin
package com.urlinspector.app.sms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.urlinspector.core.ScanUrlUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SmsContentObserverService : Service() {

    private val scanUrlUseCase: ScanUrlUseCase by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var coordinator: SmsScanCoordinator
    private lateinit var observer: ContentObserver
    private var lastSeenTimestampMillis = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        coordinator = SmsScanCoordinator(scanUrlUseCase, AndroidScanNotifier(this))

        startForeground(FOREGROUND_NOTIFICATION_ID, buildStatusNotification())

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                serviceScope.launch { processNewMessages() }
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun processNewMessages() {
        try {
            contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastSeenTimestampMillis.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    lastSeenTimestampMillis = maxOf(lastSeenTimestampMillis, cursor.getLong(dateIndex))
                    coordinator.processMessageBody(cursor.getString(bodyIndex))
                }
            }
        } catch (e: SecurityException) {
            // READ_SMS was revoked while this service was running (e.g.
            // via system Settings) — stop scanning immediately rather
            // than continuing to fail silently on every future change.
            stopSelf()
        }
    }

    private fun buildStatusNotification() =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_ID_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("URL Inspector")
            .setContentText("Watching for links in text messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SmsContentObserverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsContentObserverService::class.java))
        }
    }
}
```

`lastSeenTimestampMillis` is initialized to "now" in `onCreate()`
specifically so a freshly (re)started service never re-scans the user's
entire existing SMS history — only messages that arrive after the
service starts. This is a deliberate product decision: retroactively
scanning years of old messages the first time a user opts in would be
surprising and slow; the feature is about *incoming* messages, matching
intent.md §3(c)'s "watches incoming SMS/RCS messages."

- [ ] **Step 2: Register the service and add permissions to the manifest**

Modify `AndroidManifest.xml`. Add these three `<uses-permission>` lines
alongside the existing `INTERNET` one (order doesn't matter, but keep
them grouped):

```xml
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

Add the `<service>` block inside `<application>`, alongside the two
existing `<activity>` blocks:

```xml
        <service
            android:name=".sms.SmsContentObserverService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Watches the SMS inbox locally to scan links in incoming messages for phishing/malware, per explicit user opt-in." />
        </service>
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/sms/SmsContentObserverService.kt \
        androidApp/src/main/AndroidManifest.xml
git commit -m "feat(androidApp): add SmsContentObserverService (foreground SMS observer)"
```

---

### Task 35: Settings screen — toggle, permission flow, navigation wiring

**Files:**
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/settings/SettingsViewModel.kt`
- Create: `androidApp/src/main/kotlin/com/urlinspector/app/settings/SettingsScreen.kt`
- Test: `androidApp/src/test/kotlin/com/urlinspector/app/settings/SettingsViewModelTest.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt`

**Interfaces:**
- Consumes: `ScanPreferences` (Task 31), `SmsContentObserverService.start`/`.stop`
  (Task 34).
- Produces: `class SettingsViewModel(preferences: ScanPreferences) :
  ViewModel()` with `val smsScanningEnabled: StateFlow<Boolean>` and
  `fun setSmsScanningEnabled(enabled: Boolean)`; `@Composable fun
  SettingsScreen(...)`; a new `"settings"` route in `AppNavHost`; a new
  "Settings" entry point on `PasteScreen`. Task 36 exercises this
  end-to-end on-device.

`SettingsViewModel` is a thin pass-through over `ScanPreferences` — its
one piece of real logic (nothing to do with permissions, which live in
the Composable/Activity layer since only they can launch a permission
request) is exposing the preference as ViewModel-scoped state, so it
gets a small unit test using a fake `ScanPreferences`.

- [ ] **Step 1: Write the failing `SettingsViewModel` test**

```kotlin
package com.urlinspector.app.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeScanPreferences(initiallyEnabled: Boolean = false) : ScanPreferences {
    private val _smsScanningEnabled = MutableStateFlow(initiallyEnabled)
    override val smsScanningEnabled: StateFlow<Boolean> = _smsScanningEnabled.asStateFlow()
    var setCallCount = 0
        private set

    override fun setSmsScanningEnabled(enabled: Boolean) {
        setCallCount++
        _smsScanningEnabled.value = enabled
    }
}

class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reflects the preference's initial value`() = runTest {
        val viewModel = SettingsViewModel(FakeScanPreferences(initiallyEnabled = false))
        assertFalse(viewModel.smsScanningEnabled.value)
    }

    @Test
    fun `reflects an initially-enabled preference`() = runTest {
        val viewModel = SettingsViewModel(FakeScanPreferences(initiallyEnabled = true))
        assertTrue(viewModel.smsScanningEnabled.value)
    }

    @Test
    fun `setSmsScanningEnabled delegates to the preferences store`() = runTest {
        val preferences = FakeScanPreferences()
        val viewModel = SettingsViewModel(preferences)

        viewModel.setSmsScanningEnabled(true)

        assertEquals(1, preferences.setCallCount)
        assertTrue(viewModel.smsScanningEnabled.value)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.settings.SettingsViewModelTest"`
Expected: FAIL with "unresolved reference: SettingsViewModel".

- [ ] **Step 3: Write `SettingsViewModel.kt`**

```kotlin
package com.urlinspector.app.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val preferences: ScanPreferences,
) : ViewModel() {
    val smsScanningEnabled: StateFlow<Boolean> = preferences.smsScanningEnabled

    fun setSmsScanningEnabled(enabled: Boolean) {
        preferences.setSmsScanningEnabled(enabled)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.urlinspector.app.settings.SettingsViewModelTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Bind `SettingsViewModel` in `AppModule.kt`**

Add alongside the other `viewModel { ... }` bindings:

```kotlin
    viewModel { SettingsViewModel(get()) }
```

(Add `import com.urlinspector.app.settings.SettingsViewModel` at the
top.)

- [ ] **Step 6: Write `SettingsScreen.kt`**

```kotlin
package com.urlinspector.app.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.urlinspector.app.sms.SmsContentObserverService
import org.koin.androidx.compose.koinViewModel

private fun hasReadSmsPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.smsScanningEnabled.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val readSmsGranted = grants[Manifest.permission.READ_SMS] == true
        if (readSmsGranted) {
            viewModel.setSmsScanningEnabled(true)
            SmsContentObserverService.start(context)
        }
        // If denied, the toggle simply stays off (state was never
        // flipped to true) — no error dialog needed for v1.
    }

    // Re-check on every resume: if the user revoked READ_SMS from system
    // Settings while this toggle was on, force it off and stop the
    // service — the second half of "revoking permission stops scanning."
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                enabled &&
                !hasReadSmsPermission(context)
            ) {
                viewModel.setSmsScanningEnabled(false)
                SmsContentObserverService.stop(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Scan text messages for links")
                Text(
                    "Off by default. Watches incoming SMS for links and shows a scan result notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        val permissionsToRequest = mutableListOf(Manifest.permission.READ_SMS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    } else {
                        viewModel.setSmsScanningEnabled(false)
                        SmsContentObserverService.stop(context)
                    }
                },
            )
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}
```

(`androidx.activity.compose.LocalActivity` is imported for parity with
this file's other Activity-scoped imports but not directly referenced —
`rememberLauncherForActivityResult` internally requires being composed
under an `Activity`/`ComponentActivity`, which both `MainActivity` and
`ShareHandlerActivity` are; remove the unused `LocalActivity` import if
your Kotlin compiler flags it as unused.)

- [ ] **Step 7: Add the `"settings"` route to `AppNavHost.kt`**

Modify `AppNavHost.kt`: add a new route constant and `composable` block.
Add near the top with the other route constants:

```kotlin
private const val ROUTE_SETTINGS = "settings"
```

Add a new parameter `onOpenSettings: () -> Unit` is not needed — instead
wire navigation directly through `navController`, matching the existing
`onOpenHistory` pattern. Add this new composable block inside the
`NavHost { ... }`, alongside the existing `ROUTE_HISTORY` block:

```kotlin
        composable(ROUTE_SETTINGS) {
            com.urlinspector.app.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
```

And update the `ROUTE_PASTE` composable's `PasteScreen(...)` call to
pass a new `onOpenSettings` callback:

```kotlin
        composable(ROUTE_PASTE) {
            PasteScreen(
                uiState = uiState,
                onScan = { url -> scanViewModel.scan(url) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                initialText = prefillText ?: "",
            )
            // ... existing LaunchedEffect(uiState) block below, unchanged
```

- [ ] **Step 8: Add the "Settings" entry point to `PasteScreen.kt`**

Modify `PasteScreen.kt`'s signature to add `onOpenSettings: () -> Unit`,
and add a second `TextButton` below the existing "View scan history"
one:

```kotlin
@Composable
fun PasteScreen(
    uiState: ScanUiState,
    onScan: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    // ... existing urlText/Column/OutlinedTextField/Button/error-Text unchanged ...

        TextButton(onClick = onOpenHistory) {
            Text("View scan history")
        }

        TextButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}
```

- [ ] **Step 9: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/settings/SettingsViewModel.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/settings/SettingsScreen.kt \
        androidApp/src/test/kotlin/com/urlinspector/app/settings/SettingsViewModelTest.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/AppNavHost.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt \
        androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt
git commit -m "feat(androidApp): add Settings screen with SMS-scanning opt-in toggle"
```

---

### Task 36: On-device/emulator verification

**Files:** none (verification only, no source changes).

This task has no code changes and needs no `task-reviewer` code-quality
pass — it is a manual verification pass, reported the same way M3's
Task 25 and M4's Task 29 were.

The Android emulator supports simulating an inbound SMS via its console
protocol, exposed through `adb emu sms send <sender-number> <message>` —
this actually inserts the message into the real on-device SMS content
provider via the emulated modem, so it is a genuine, faithful test of
the `ContentObserver` path (not a synthetic shortcut).

- [ ] **Step 1: Build and install the debug APK**

```bash
./gradlew :androidApp:installDebug
```

- [ ] **Step 2: Launch the app and enable SMS scanning**

```bash
adb shell am start -n com.urlinspector.app/.MainActivity
```

On the paste screen, tap "Settings", then toggle "Scan text messages for
links" on. Accept the `READ_SMS` (and, if prompted, notification)
permission dialogs. Confirm with a screenshot
(`adb exec-out screencap -p`) that the toggle is now on, and confirm via
`adb shell dumpsys activity services com.urlinspector.app` that
`SmsContentObserverService` is running.

- [ ] **Step 3: Simulate an inbound SMS containing a URL**

```bash
adb emu sms send 5551234567 "Your delivery is delayed, track it: https://example.com/track123"
```

Expected: within a couple of seconds, a notification titled "Safe link
found in a text message" (or Suspicious/Malicious, depending on what the
heuristics/reputation fallback produce for this URL) appears, containing
`https://example.com/track123`. Confirm with a screenshot.

- [ ] **Step 4: Tap the notification and confirm it opens the verdict screen**

Tap the notification (via `adb shell input tap` at the notification
shade's location, after pulling down the shade with `adb shell cmd
statusbar expand-notifications`, or by locating its bounds with
`uiautomator dump` as done in prior milestones). Expected: the app opens
directly to the verdict screen for `https://example.com/track123`
(reusing `ShareHandlerActivity`'s existing auto-scan flow from M4).
Confirm with a screenshot.

- [ ] **Step 5: Confirm the SMS-detected scan is recorded in history**

Navigate to "View scan history" and confirm the `example.com/track123`
entry is present.

- [ ] **Step 6: Confirm toggling off stops scanning**

Navigate to Settings, toggle "Scan text messages for links" off. Confirm
via `adb shell dumpsys activity services com.urlinspector.app` that
`SmsContentObserverService` is no longer running. Send another SMS:

```bash
adb emu sms send 5551234567 "Second test: https://example.com/should-not-scan"
```

Expected: no new notification appears, and (after reopening the app) no
new `should-not-scan` entry shows up in history.

- [ ] **Step 7: Confirm revoking the permission also stops scanning**

Re-enable the toggle (Step 2) so the service is running again. Then
revoke the permission directly, bypassing the app's own toggle:

```bash
adb shell pm revoke com.urlinspector.app android.permission.READ_SMS
```

Send another SMS:

```bash
adb emu sms send 5551234567 "Third test: https://example.com/revoked-test"
```

Expected: no notification (the service's `ContentObserver` query throws
`SecurityException` and the service stops itself). Then bring the app to
the foreground and navigate to Settings — expected: the toggle has
flipped to off on its own (the `ON_RESUME` re-check in `SettingsScreen`
caught the revoked permission). Confirm both outcomes with screenshots.

No commit needed for this task (no source changes) — report the outcome
directly: which steps passed, any screenshots taken, and confirmation
that the M5 exit criteria (docs/plan.md's M5 goal, intent.md FR3) are
met on a real running emulator, not just in unit tests.

---

## M6 Global Constraints

These bind Tasks 37-42 below.

- **Scope decisions confirmed with the user before this pass began**
  (mirrors the scope-narrowing pattern already used in M3/M4/M5 — stated
  explicitly rather than silently assumed):
  - **No production signing keystore.** M6 configures a `release`
    `buildType` (minification/shrinking, ProGuard rules) but does NOT
    generate or reference a real Play-Store signing identity — that is a
    decision only the app's owner can make (organization identity,
    key-rotation policy, Play Console enrollment), not something to
    invent. The `release` build in this pass is signed with the debug
    signing config purely so `assembleRelease`/`installRelease` can be
    built and smoke-tested; this is explicitly documented as NOT
    production-ready signing, with a `TODO` marking the follow-up.
  - **No instrumented (`androidTest`) test suite.** spec.md §14 calls for
    Android instrumented tests; this project has relied on real
    on-device/emulator manual verification instead at every milestone
    since M3, and that established precedent continues for M6 rather
    than introducing a first instrumented-test investment this late.
    "Full instrumented test pass" is satisfied here by the on-device
    verification in Task 42, not by new `androidTest` code.
  - **Safe Browsing API key: mechanism only, key stays empty.** Task 38
    moves the key out of a hardcoded Kotlin constant into
    `local.properties`/`BuildConfig` (real secrets-management hygiene —
    never committed, never hardcoded), but no real key value is supplied
    in this pass. The DR2 heuristics-only fallback behavior already
    built and tested in M2 is unchanged; this task is purely about
    *how* the key would reach the app when one is eventually supplied.
- **Reuses from M1-M5, do not reimplement**: `core.ScanUrlUseCase`'s
  `runGuarded`/timeout/DR2-fallback machinery (already the mechanism
  behind every "offline"/error-path requirement in spec.md §13 — already
  unit-tested in `ScanUrlUseCaseTest` for both the MALICIOUS-verdict path
  and the reputation-timeout-falls-back-to-heuristics path); the existing
  `ScanViewModel`/`SmsScanCoordinator` exception-boundary patterns
  (`catch (e: CancellationException) { throw e }` before a broader
  catch) established across M3-M5 — Task 40 audits for gaps against this
  existing pattern, it does not invent a new one.
- **Verdict-rendering note**: the MALICIOUS verdict's on-device visual
  presentation (color/label in `VerdictScreen`) cannot be exercised
  end-to-end without a real Safe Browsing API key returning a genuine
  threat match — since M6 keeps the key empty (see above), this stays
  covered by `ScanUrlUseCaseTest`'s existing unit-level verification
  only, not a new on-device check. Revisit once a real key is supplied.
- Dependency versions: no new dependencies are required for Tasks 37-41.
  R8/minification uses AGP's bundled tooling; `kotlinx.serialization`,
  Ktor, SQLDelight, and Koin all ship their own consumer ProGuard rules
  in their published artifacts — Task 39 adds a minimal project-level
  `proguard-rules.pro` defensively and, critically, *empirically verifies*
  the release build actually runs (R8 misconfiguration is a classic
  build-succeeds-but-crashes-at-runtime failure mode that no amount of
  reading consumer-rules.pro files substitutes for).
- Out of scope for M6: iOS work (spec.md §15, explicitly a future
  follow-up), a real production signing identity (see above), RCS
  scanning changes (already out of scope since M5), any new user-facing
  feature.

---

## M6 — Hardening & Release Readiness: Detailed Tasks

### Task 37: Network security config

**Files:**
- Create: `androidApp/src/main/res/xml/network_security_config.xml`
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Interfaces:** none (manifest/resource-only change, no code consumes
this).

spec.md §12 explicitly calls for "Android network security config
disallowing cleartext" as part of this app's security posture. Android
already defaults to disallowing cleartext for apps targeting API 28+
(this app's `targetSdk = 35` already gets that default), but an explicit
config makes the policy auditable or reversible for a specific domain
rather than resting entirely on a platform default — the honest,
documented version of what spec.md asks for.

This task has no automated test (a manifest/XML resource change) —
verified by `assembleDebug` succeeding and, since there is no code path
to exercise, that is sufficient; Task 42's on-device pass additionally
confirms real HTTPS traffic (Safe Browsing lookups) still works
correctly with this config in place.

- [ ] **Step 1: Write `network_security_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 2: Reference it from the manifest**

Add `android:networkSecurityConfig="@xml/network_security_config"` to the
`<application>` tag in `AndroidManifest.xml`, alongside the existing
`android:allowBackup="false"` etc. attributes:

```xml
    <application
        android:name=".UrlInspectorApp"
        android:allowBackup="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:label="URL Inspector"
        android:theme="@style/Theme.UrlInspector">
```

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/res/xml/network_security_config.xml \
        androidApp/src/main/AndroidManifest.xml
git commit -m "feat(androidApp): add explicit network security config disallowing cleartext"
```

---

### Task 38: Safe Browsing API key via `local.properties`/`BuildConfig`

**Files:**
- Modify: `androidApp/build.gradle.kts`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt`
- Modify: `local.properties` (gitignored — this task documents the key,
  it does not commit one)

**Interfaces:**
- Consumes: nothing new.
- Produces: `BuildConfig.SAFE_BROWSING_API_KEY: String` (generated by
  AGP from the `buildConfigField` declared in this task), consumed by
  `AppModule.kt` in place of the current hardcoded empty-string constant.

This task has no automated test — it is Gradle build-config plumbing.
Verified by `assembleDebug` succeeding and by confirming (via the
generated `BuildConfig.java`/Kotlin source, or simply by reasoning about
the fallback default) that an absent/empty key in `local.properties`
still produces an empty string at runtime — i.e., this task must not
change DR2's existing empty-key-falls-back-to-heuristics behavior,
already covered by M2's `ScanUrlUseCaseTest`/`SafeBrowsingClientTest`.

- [ ] **Step 1: Enable `buildConfig` and read the key from `local.properties`**

Modify `androidApp/build.gradle.kts`. Add this near the top, before the
`android { }` block:

```kotlin
import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
```

Inside the existing `android { }` block, add:

```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.urlinspector.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "SAFE_BROWSING_API_KEY",
            "\"${localProperties.getProperty("safeBrowsingApiKey", "")}\"",
        )
    }
```

(This adds `buildConfigField(...)` and the second `buildFeatures.buildConfig
= true` line to the EXISTING `defaultConfig`/`buildFeatures` blocks —
read the current file first and merge into them, don't create duplicate
blocks.)

- [ ] **Step 2: Document the key in `local.properties`**

Append to the (gitignored) `local.properties` file — this is a local,
uncommitted convenience for development, not a secret being committed:

```properties
# Optional: a real Google Safe Browsing v4 API key. Leave unset/empty to
# keep the app's existing heuristics-only fallback behavior (DR2).
safeBrowsingApiKey=
```

- [ ] **Step 3: Consume it from `AppModule.kt`**

Replace:

```kotlin
// TODO(future milestone): real key provisioning (build config / secrets
// management) is out of scope for M3. An empty key means every reputation
// lookup fails (400/403), which ScanUrlUseCase.runGuarded already turns
// into a graceful heuristics-only fallback — this is intended, working
// DR2 behavior for now, not a bug.
private const val SAFE_BROWSING_API_KEY = ""
```

with:

```kotlin
// Real key provisioning: supply `safeBrowsingApiKey=...` in the
// (gitignored) local.properties file — see androidApp/build.gradle.kts's
// buildConfigField wiring. An empty/absent key means every reputation
// lookup fails (400/403), which ScanUrlUseCase.runGuarded already turns
// into a graceful heuristics-only fallback — this is intended, working
// DR2 behavior, not a bug.
private val SAFE_BROWSING_API_KEY = BuildConfig.SAFE_BROWSING_API_KEY
```

Add the import `import com.urlinspector.app.BuildConfig` at the top of
`AppModule.kt` (AGP generates this class in the app's own package at
build time from the `applicationId`/`namespace`, `com.urlinspector.app`).

- [ ] **Step 4: Verify it builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL, with `local.properties`'s
`safeBrowsingApiKey` left empty — confirm the generated
`BuildConfig.SAFE_BROWSING_API_KEY` is `""` by inspecting
`androidApp/build/generated/source/buildConfig/debug/com/urlinspector/app/BuildConfig.java`
(or the equivalent Kotlin-generated path — AGP 9.x may generate either;
check whichever exists) after the build.

- [ ] **Step 5: Commit**

```bash
git add androidApp/build.gradle.kts androidApp/src/main/kotlin/com/urlinspector/app/di/AppModule.kt
git commit -m "feat(androidApp): move Safe Browsing API key to BuildConfig/local.properties"
```

(Do not `git add local.properties` — it is gitignored; if your `git add`
step attempts to stage it and git refuses/ignores it, that is correct
and expected, not an error.)

---

### Task 39: Release build type — minification, shrinking, ProGuard rules

**Files:**
- Modify: `androidApp/build.gradle.kts`
- Create: `androidApp/proguard-rules.pro`

**Interfaces:** none new — this task must not change any public
class/function signature; its entire job is making the EXISTING code
survive R8 minification unchanged.

No automated test — verified by `./gradlew :androidApp:assembleRelease`
succeeding (build-time proof) AND, critically, by Task 42's on-device
install-and-smoke-test of the actual release APK (the only real proof
R8 didn't silently break something at runtime — a build that compiles
under R8 can still crash on first launch if a reflection-dependent class
got stripped).

- [ ] **Step 1: Add the `release` build type**

Modify `androidApp/build.gradle.kts`'s `android { }` block, adding a
`buildTypes { }` block (a sibling of the existing `defaultConfig { }`
and `compileOptions { }` blocks):

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // TODO(follow-up, not part of M6): this reuses the debug
            // signing config purely so assembleRelease/installRelease can
            // be built and smoke-tested on a development machine. This is
            // NOT a production signing identity — before any real
            // distribution (Play Store or otherwise), replace this with a
            // real release signingConfig backed by a properly-secured
            // keystore (never committed to source control).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
```

- [ ] **Step 2: Write `proguard-rules.pro`**

```proguard
# kotlinx.serialization: the plugin generates serializers at compile time
# (not reflection-based), and the library ships its own consumer rules,
# but keep the DTOs' Companion objects explicitly as defense in depth —
# R8 has historically had edge cases around synthetic $serializer classes
# when aggressive optimization is combined with shrinking.
-keepclassmembers class com.urlinspector.data.reputation.** {
    *** Companion;
}
-keepclasseswithmembers class com.urlinspector.data.reputation.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLDelight-generated database/query classes are constructed reflectively
# in a few internal paths; keep the generated package intact.
-keep class com.urlinspector.data.db.** { *; }

# Koin builds its dependency graph via a DSL, not reflection, but keep
# the DI module's declared types' constructors reachable defensively.
-keepclassmembers class com.urlinspector.app.** {
    public <init>(...);
}
```

- [ ] **Step 3: Verify the release build actually compiles and packages**

Run: `./gradlew :androidApp:assembleRelease`
Expected: BUILD SUCCESSFUL, producing
`androidApp/build/outputs/apk/release/androidApp-release.apk`.

- [ ] **Step 4: Commit**

```bash
git add androidApp/build.gradle.kts androidApp/proguard-rules.pro
git commit -m "feat(androidApp): add release build type with R8 minification and ProGuard rules"
```

(Task 42 is where this release APK actually gets installed and
exercised on-device — this task's own scope stops at "it builds.")

---

### Task 40: Exception-boundary hardening audit

**Files:**
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/LinkOpener.kt`
- Modify: `androidApp/src/main/kotlin/com/urlinspector/app/scan/PasteScreen.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun Activity.openExternalLink(url: String)` keeps its exact
  signature; its body changes to fail safely instead of crashing.

spec.md §13 requires: "Any uncaught exception in the detection pipeline
is caught at the use case boundary and surfaced as a generic 'scan
failed, try again' state rather than crashing the host Activity/Service."
`core.ScanUrlUseCase`, `ScanViewModel`, and `SmsScanCoordinator` already
satisfy this (verified in M1/M3/M5's own reviews) — this task closes the
one remaining gap found during this audit: `openExternalLink` calls
`startActivity(Intent(ACTION_VIEW, ...))` with no guard, which throws
`ActivityNotFoundException` and crashes the host Activity on any device
with no browser/handler installed for the URL's scheme (rare on a real
phone, but a real crash path — the emulator image happens to always have
Chrome, which is exactly why this was never caught by earlier on-device
QA).

- [ ] **Step 1: Write the failing test**

`Activity`-scoped extension functions can't be unit-tested without an
Android framework/Robolectric dependency this project doesn't have (same
established limitation as every other Activity-glue file in this
codebase — see M3/M4/M5's own task briefs for this exact reasoning).
Skip to Step 2; this is verified by code review + Task 42's on-device
pass instead.

- [ ] **Step 2: Guard `openExternalLink` against a missing handler**

Replace the full contents of `LinkOpener.kt`:

```kotlin
package com.urlinspector.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun Activity.openExternalLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 3: Verify it builds**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/kotlin/com/urlinspector/app/LinkOpener.kt
git commit -m "fix(androidApp): guard openExternalLink against ActivityNotFoundException"
```

(No `PasteScreen.kt` change is actually required by this task — it was
listed above as a candidate during planning but the audit found no
additional gap there; if your own reading of the current file confirms
this, leave it untouched and note that in your report rather than
making a speculative change.)

---

### Task 41: Security review pass

**Files:** none required (a review task; any findings it surfaces get
fixed as part of this task's own fix loop, files TBD by what's found).

This task is a deliberate, structured security review of the whole
`androidApp` (plus a light pass over `core`/`data` for anything
security-relevant), covering exactly what spec.md §12 calls for:

- [ ] **Step 1: Review permissions**

Confirm (read `AndroidManifest.xml`): `INTERNET` (always), `READ_SMS`/
`POST_NOTIFICATIONS`/`FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_SPECIAL_USE`
(SMS-scanning-related, all requested only via the M5 opt-in flow, never
at first launch — verify by re-reading `SettingsScreen.kt`'s permission
request call site, not just the manifest declaration). No permission
should be present that isn't justified by a specific, traceable feature.

- [ ] **Step 2: Review data handling**

Confirm: no SMS message body, no scan URL's full response body, and no
API key value is ever passed to `android.util.Log`/`println` anywhere in
`androidApp`, `core`, or `data` (grep for `Log\.` and `println` across
all three `src/main` trees — expect zero hits, matching M5's own
established finding). Confirm `ScanHistoryEntry` persists only URL,
verdict, and timestamp (re-read `core/model/Models.kt` — no behavior
change expected, just confirmation). Confirm `android:allowBackup="false"`
is still present in the manifest (the M3 Critical fix — regression-check
it hasn't drifted).

- [ ] **Step 3: Review build/secrets hygiene**

Confirm `local.properties` is listed in `.gitignore` (it must already
be, since `sdk.dir` has lived there since M3 — this step is a
regression-check, not new work). Confirm Task 38's `safeBrowsingApiKey`
plumbing never ends up in a committed file, a log statement, or a
`BuildConfig` field that's readable by anything other than this app's
own process (it isn't — `BuildConfig` fields compile into the APK's
`.dex`, decompilable like any local secret baked into a client app; note
this as an inherent limitation of client-side API keys, not a defect
introduced by this task — a server-side proxy would be the real fix, and
is out of scope).

- [ ] **Step 4: Review network security**

Confirm Task 37's network security config is present and correctly
referenced. Confirm both `HttpClient` instances in `AppModule.kt`
(`REPUTATION_HTTP_CLIENT`, `EXPANDER_HTTP_CLIENT`) use `https://` base
URLs / correctly-scoped redirect policy (re-read `SafeBrowsingClient.kt`'s
`DEFAULT_BASE_URL` and `HttpUrlExpander.kt` — regression-check, no
change expected).

- [ ] **Step 5: Record findings and fix**

If this review surfaces any real finding (something a step above didn't
already predict as "expected, no change"), fix it as part of this same
task — write the fix, verify with the relevant existing test suite or
`assembleDebug`, and note it explicitly in your task report. If the
review comes back clean (all of Steps 1-4 confirm existing, correct
behavior with no drift), that is a valid, complete outcome for this
task — report it as such rather than manufacturing a finding.

- [ ] **Step 6: Commit** (only if Step 5 produced a fix; skip if clean)

```bash
git add <whatever files Step 5's fix touched>
git commit -m "fix(androidApp): <describe the specific security finding fixed>"
```

---

### Task 42: Final on-device release-build verification

**Files:** none (verification only, no source changes).

This task has no code changes and needs no `task-reviewer` code-quality
pass — it is the milestone's actual exit-criteria proof, reported the
same way M3's Task 25, M4's Task 29, and M5's Task 36 were.

- [ ] **Step 1: Build and install the RELEASE (not debug) APK**

```bash
./gradlew :androidApp:installRelease
```

Expected: succeeds (proves Task 39's R8/minification setup produces an
installable APK, not just a compiling one).

- [ ] **Step 2: Launch and smoke-test the manual paste flow**

```bash
adb shell am start -n com.urlinspector.app/.MainActivity
```

Paste a URL, tap Scan, confirm the verdict screen renders correctly (no
crash, no missing-class R8 stripping artifact). Confirm "Open link
anyway" opens a browser without crashing (Task 40's regression target).
Confirm "View scan history" renders correctly. Screenshot each step.

- [ ] **Step 3: Smoke-test the share-sheet flow**

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://example.com/release-test" \
  -n com.urlinspector.app/.ShareHandlerActivity
```

Confirm it opens directly to the verdict screen (M4's flow, now under
R8). Screenshot.

- [ ] **Step 4: Smoke-test the SMS-scanning opt-in flow**

Navigate to Settings, toggle SMS scanning on, grant permissions, confirm
the foreground service starts (`adb shell dumpsys activity services
com.urlinspector.app` shows `isForeground=true`). Send a test SMS via
`adb emu sms send 5551234567 "release test: https://example.com/sms-release-test"`
and confirm a notification appears and its tap opens the verdict screen
(M5's flow, now under R8). Toggle off afterward. Screenshot.

- [ ] **Step 5: Smoke-test the offline/error fallback path**

```bash
adb shell svc wifi disable
adb shell svc data disable
```

Scan a URL manually. Expected: the verdict screen still renders (from
heuristics alone), showing the "Reputation check could not be
completed — showing on-device checks only" DR2 message (spec.md §13's
explicit requirement) rather than hanging or crashing. Screenshot. Then
restore connectivity:

```bash
adb shell svc wifi enable
adb shell svc data enable
```

- [ ] **Step 6: Confirm the debug test suite is still green**

```bash
./gradlew test testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all suites passing (this final run is the
"all tests green" half of M6's exit criteria — the release-build smoke
test in Steps 1-5 is the "manual QA pass on the three user flows...
release build installs and runs on a target device" half).

No commit needed for this task (no source changes) — report the outcome
directly: which steps passed, any screenshots taken, and confirmation
that M6's exit criteria (docs/plan.md's own M6 goal, spec.md §12/§13/§14
as scoped by this milestone's Global Constraints) are met on a real
release build running on a real emulator, not just compiled.
