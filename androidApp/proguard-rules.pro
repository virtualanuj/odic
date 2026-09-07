# kotlinx.serialization, Ktor, SQLDelight, and Koin all ship their own
# consumer ProGuard rules bundled in their published artifacts (verified by
# inspecting androidApp/build/outputs/mapping/release/configuration.txt during
# Task 39's release build) — the DTOs' $$serializer classes, Koin's DI graph,
# and Ktor's CIO engine ServiceLoader registration are all correctly kept
# by those consumer rules without any project-level rule needed here.
# SQLDelight-generated code uses no reflection at all and needs no keep rule.
#
# This file intentionally starts empty. If a future dependency addition (or
# an R8 crash discovered on a real device) requires a project-level rule,
# add it here with a comment citing the specific class/behavior it protects
# and how you verified the rule is actually needed (e.g. by removing it and
# checking mapping/release/seeds.txt or reproducing the crash).
