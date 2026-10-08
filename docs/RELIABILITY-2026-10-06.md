# Existing feature reliability

Base: `adf2683ebdef8ffe55fed9a491ae2f8386a6a845` (merged promise/tree and prompt/runtime audits).

## Changes

- Prompt editor rejects empty prompts, missing/unknown template inputs, unmatched braces, malformed journey days and incomplete test scenarios. Errors stay visible; Save/Reset report success only after persistence and cannot overlap in this editor.
- Prefix, prompt-override and journey-catalog caches discard reads invalidated while suspended. Prompt callbacks can be registered safely during invalidation.
- Each chat request supplies authoritative current journey, catalog, onboarding and help numbers after the cached prefix. An explicitly cleared help list remains empty.
- Talk and Path observe prompt/custom-plan catalog changes directly, even without changing a journey run.
- Path refreshes its local calendar date while subscribed (within 30 seconds of midnight or a clock/zone change). A fresh subscription reads the day immediately. Starting today's step checks availability again at execution time.
- Backup input and decompressed JSON have explicit size limits. Export uses the same limits, preventing creation of an oversized backup that the importer would refuse.
- Restore rejects unsupported formats, invalid/duplicate core IDs, orphan messages and multiple open conversations/current journeys before deleting any data. Schema compatibility checks remain in force.
- Imported streaming messages with text become interrupted/retryable; empty streaming messages are discarded. Queued user messages retain their status.
- Restore/erase block new long-running writers, cancel admitted chat/reflection/letter/snapshot/gardening/growth/journey writers and wait for their cleanup before replacing rows. Maintenance index/monthly work uses the same coordination. Dataset-derived UI caches, reflection failure/give-up flags, receipts, private chat and viewed-conversation state are cleared after commit; device preferences stay.

## Validation

New regressions exercise bundled prompt compatibility, invalid fields/catalog/bench structure, suspended prefix invalidation, volatile state ordering, calendar changes without DB writes, explicitly cleared help numbers, backup boundaries/formats/identities and a real Room restore preserving old rows when validation fails.

The existing suite covers provider parsing/cancellation, reply completion/fallback, promise times and edits, alarms, memory integrity, forgetting/search, Room migrations, onboarding, journeys/adaptation, letters, tree geometry, settings, secrets and cost calculations. CI runs the entire unit suite and the R8 release build. CI results are recorded in the PR.

Local Gradle execution could not download its distribution through this environment's network route; this is not recorded as a passing local test.

## Remaining integration checks

These source fixes and unit tests do not certify every device/provider behavior:

1. Shared writer coordination now has deterministic cancellation/admission/nesting tests and a Room restore test using a cancellation cleanup that writes an old reused ID. Live provider and installed-device restore/erase checks should still verify the entire UI transition.
2. On Redmi/HyperOS: notification permission, exact/inexact alarm delivery, reboot, background/locked-screen replies, battery restrictions and upgrade smoke testing.
3. In the installed app: promise editing after midnight, cancel/retry/offline reconnect, large text, tree pan/zoom/touch selection, all onboarding steps, journey pause/resume, backup restore after process restart and prompt bench replies from live providers.
4. AI prose remains model dependent. The runtime supplies current facts and validates actions, but cannot guarantee the quality or factual accuracy of every generated sentence.

The supplied prompt Markdown assets are unchanged. No dependency or database schema change is required.
