# Intent: Malicious URL Inspector for SMS/WhatsApp Messages

## 1. Overview

Phishing and scam links are frequently delivered to mobile users via SMS,
RCS, and WhatsApp messages. These channels offer no built-in way to check
whether a link is safe before tapping it. This application lets a user
inspect a URL received in a text or WhatsApp message and get a clear
verdict — Safe, Suspicious, or Malicious — along with the reasons behind
that verdict, before they decide to open it.

## 2. Goals / Non-Goals

**In scope:**
- Android application.
- Three ways to get a URL into the app: OS share sheet, manual paste, and
  optional scanning of the device's SMS/RCS inbox.
- URL scanning via an online reputation lookup combined with on-device
  heuristics.
- A verdict screen showing the result and the reasons behind it.
- A local history of past scans.

**Out of scope (future extensions, not required now):**
- iOS support.
- Live sandboxed fetching/rendering of the destination page (redirect
  chains, page content, TLS certificate inspection).
- Automatically blocking the user from opening a malicious link — the app
  informs, it does not enforce.

## 3. User Flows

**a) Share from WhatsApp / Messages**
User long-presses a link in WhatsApp or the Messages app, taps Share, and
selects this app from the share sheet. The app opens directly to the
verdict screen for that URL.

**b) Manual paste**
User opens the app, pastes or types a URL into a text field, and taps
Scan. The app shows the verdict screen for that URL.

**c) SMS/RCS inbox scanning (optional feature)**
If the user has granted SMS access (or set this app as the default SMS
app), the app watches incoming SMS/RCS messages for URLs. When a URL is
detected in an incoming message, the app surfaces a notification prompting
the user to review the scan result. This flow is opt-in and disabled by
default.

## 4. Functional Requirements

- FR1: The app MUST be selectable as a share target from other apps (e.g.
  WhatsApp, Messages) so a link can be shared into it directly.
- FR2: The app MUST provide a manual URL entry screen with input
  validation (well-formed URL check) before scanning.
- FR3: The app MAY offer an opt-in mode that scans incoming SMS/RCS
  messages for URLs and notifies the user of the scan result. This
  requires either the `READ_SMS` permission or the app being set as the
  default SMS handler, and must be off by default.
- FR4: For every scanned URL, the app MUST produce a verdict of Safe,
  Suspicious, or Malicious, along with a list of the specific reasons that
  produced that verdict.
- FR5: The app MUST NOT block the user from opening a flagged link; it
  presents the verdict and reasons, and the user chooses whether to
  proceed.
- FR6: The app MUST keep a local, on-device history of scanned URLs, their
  verdicts, and timestamps, viewable by the user.
- FR7: The user MUST be able to delete individual entries or clear the
  entire scan history.

## 5. Detection Requirements

Each scan combines two signal sources; the verdict is derived from the
combination, not either source alone.

**Reputation lookup (online)**
- DR1: The app MUST query a threat-intelligence/reputation API (e.g. a
  Google Safe Browsing-style lookup) with the URL and incorporate the
  result (known phishing/malware/unwanted-software listing) into the
  verdict.
- DR2: If the reputation service is unreachable, the app MUST still
  produce a verdict from local heuristics alone and indicate that the
  reputation check could not be completed.

**Local heuristics (on-device, no network required)**
- DR3: Typosquat / lookalike domain detection against a list of commonly
  impersonated brands/services (e.g. `paypa1.com`, `arnazon.com`).
- DR4: Suspicious or high-abuse top-level domain detection (e.g. rare or
  frequently-abused TLDs).
- DR5: URL shortener detection with expansion of the shortened URL (e.g.
  `bit.ly`, `tinyurl.com`) so the underlying destination can also be
  checked by DR1–DR4.
- DR6: IP-literal host detection (URLs using a raw IP address instead of a
  domain name).
- DR7: Homograph / punycode detection (domains using look-alike Unicode
  characters, e.g. Cyrillic characters mimicking Latin ones).

**Verdict derivation**
- DR8: A hit from the reputation lookup (DR1) alone is sufficient to
  produce a Malicious verdict.
- DR9: One or more heuristic hits (DR3–DR7) without a reputation hit
  produce a Suspicious verdict.
- DR10: No hits from either source produce a Safe verdict.

## 6. Platform & Permissions

- Target platform: Android.
- Sharing a link into the app (FR1) requires no special permission.
- Network access is required for the reputation lookup (DR1).
- Access to SMS/RCS messages is required only if the user opts into
  SMS/RCS inbox scanning (FR3); the app MUST NOT request this access
  otherwise.

## 7. Privacy

- The URL being scanned is sent to the reputation API; no other message
  content, contact information, or metadata is transmitted.
- Scan history (FR6) is stored locally on the device only; it is not
  synced or uploaded anywhere.
- SMS/RCS inbox scanning (FR3), if enabled, only inspects messages for
  URLs locally on-device; only extracted URLs are sent to the reputation
  API, not full message bodies.

## 8. Open Questions / Future Extensions

- iOS support (Share Extension-based, no SMS inbox scanning due to
  platform restrictions).
- Live sandboxed fetch/render of the destination page for deeper analysis
  (final redirect chain, page content, certificate inspection).
- Optional auto-block mode for users who want malicious links blocked
  outright rather than just warned.
- Choice of specific reputation API provider(s) and their rate limits/cost.
