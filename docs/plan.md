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
breakdown for **M0 (project scaffolding), M1 (core domain), and M2 (data
layer)** — the part of the plan that is buildable and testable today on
plain JVM, since the Android SDK is not yet installed on the development
machine. M3–M6 are kept as milestone-level summaries at the end; each
gets the same bite-sized treatment in its own pass once it's ready to
start.

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

## Future Milestones (M3–M6, summary only — detailed per-task breakdown to follow when each is ready to start)

### M3 — Android UI & manual flow
- **Goal**: user can manually paste a URL and see a verdict, backed by
  real detection.
- **Touches**: `androidApp` — paste screen, verdict screen, history list
  screen, view model, DI wiring (spec.md §11 Dependency Injection).
- **Exit criteria**: manual-paste flow works end-to-end on-device;
  history screen lists, deletes, and clears entries (intent.md FR6/FR7).
- **Blocked on**: Android SDK installation (not yet present on this
  machine) and the `core` → true KMP conversion noted in Global
  Constraints above.

### M4 — Share-sheet integration
- **Goal**: links shared from WhatsApp/Messages open directly to a
  verdict.
- **Touches**: `androidApp` — share-target registration + handling
  screen (spec.md §8 Platform Integration), reusing M3's verdict screen.
- **Exit criteria**: sharing a link from WhatsApp/Messages into the app
  shows the correct verdict; instrumented test covers this path.

### M5 — Opt-in SMS scanning
- **Goal**: optional proactive detection of links in incoming SMS/RCS.
- **Touches**: `androidApp` — settings toggle, SMS permission flow,
  inbox observer (spec.md §8 Platform Integration), detected-link
  notification.
- **Exit criteria**: with the toggle on and permission granted, an
  incoming SMS containing a URL produces a notification with the correct
  verdict; toggling off or revoking permission stops scanning (verified
  by instrumented test).

### M6 — Hardening & release readiness
- **Goal**: production-ready build.
- **Touches**: error/offline fallback paths (spec.md §13 Error Handling)
  exercised across all entry points, full instrumented test pass
  (spec.md §14 Testing Strategy), security review of permissions and
  data handling (spec.md §12 Permissions & Security), release build/
  signing config.
- **Exit criteria**: all tests (unit + instrumented) green; manual QA pass
  on the three user flows from intent.md §3 with network on and off;
  release build installs and runs on a target device.
