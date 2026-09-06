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
breakdown for **M0 (project scaffolding) and M1 (core domain)** — the part
of the plan that is buildable and testable today on plain JVM, since the
Android SDK is not yet installed on the development machine. M2–M6 are
kept as milestone-level summaries at the end; each gets the same
bite-sized treatment in its own pass once it's ready to start.

## Global Constraints

- Kotlin 2.4.10, Gradle 9.7.1 — the versions already installed on this
  machine; use them verbatim in build files.
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
                    val reputationDeferred = async { checkReputation(expandedUrl) }

                    typosquatDeferred.await()?.let { findings.add(it) }
                    tldDeferred.await()?.let { findings.add(it) }

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

## Future Milestones (M2–M6, summary only — detailed per-task breakdown to follow when each is ready to start)

### M2 — Data layer
- **Goal**: real reputation and storage implementations behind the
  domain interfaces.
- **Touches**: `data` — HTTP client setup, `SafeBrowsingReputationProvider`
  (spec.md §6), storage schema + `ScanRepository` impl (spec.md §9
  Storage), in-memory reputation cache (spec.md §10 Networking &
  Concurrency).
- **Exit criteria**: `data` tests pass against a mocked HTTP client and an
  in-memory storage driver; `ScanUrlUseCase` runs end-to-end against real
  (or mocked) network in an integration test.

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
