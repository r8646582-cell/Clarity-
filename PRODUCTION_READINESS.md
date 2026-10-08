# Purpose 1.3.0 reliability assessment

This release fixes concrete memory and execution defects. It is not a guarantee of flawless AI responses or maintenance-free lifetime use.

## Changes

- Apply the uploaded prompt batch: one canonical action protocol, natural-language dates resolved by code, corrections over older claims, and prospective wording until execution receipts arrive.
- Refuse to merge explicit denials with their opposite statements merely because their words overlap.
- Reject duplicate tool calls in one reply. Journey starts and advances report success only when the repository actually changed state.
- Preserve known and unknown source history when notes are synthesized or merged. Keep strengths from different conversations separate because the current schema stores only one source per strength.
- Reject replayed or older reflection results and direct off-the-record reflection writes.
- Invalidate cached chat context after memory corrections and updates; observe gardening and snapshot changes too.
- Retrieve matching live memories across multiple terms, rank actual word matches, preserve uncertainty labels, and omit unrelated fallback notes.
- Read live notes separately from the lifetime archive for routine learning/gardening. Archive history stays in encrypted storage and remains searchable.
- Enforce the chat context budget even when protected profile entries, portraits, letters, chapters, or continuity summaries alone exceed it. Mark omissions explicitly; no stored history is truncated.
- Verify encrypted backup bytes by reading the storage provider's saved file back and comparing size and SHA-256. An unverifiable automatic copy never triggers pruning of older recovery copies.

## Automated verification

CI runs the complete `:app:testDebugUnitTest` suite, then assembles the minified release APK. The suite includes real SQLite/Room migration, restore, memory protection, action execution, provider/streaming, reminder/time, journey, growth-tree, and test-bench parser tests. Results are uploaded as an artifact.

The existing current-APK workflow also smoke-tests the debug build on API 34. The release APK then undergoes emulator fresh-install and same-build reinstall smoke checks on API 28 and 35. This proves launch/reinstall behavior on those emulators, not an upgrade from a previously signed production release. The existing release smoke workflow can separately test old/new published releases with the production signing identity.

## Signed installation artifact

Changing `app/build.gradle.kts` on `main` automatically runs the existing signed-release pipeline. It requires the persistent signing secrets, runs the full unit suite, builds the minified APK, verifies its signer, and publishes `v<versionName>`. The version must be bumped for each production update. Tag/manual release triggers remain available. PR install/reinstall smoke checks gate this change's merge.

## Remaining device/provider checks

- The in-app test bench requires a configured AI provider/key and human judgments. Parser and execution tests cannot establish the quality or truthfulness of every future model response.
- Verify the production-signed APK installs over the app currently on the phone. Export a backup before updating; do not uninstall to bypass a signing mismatch.
- Restore an encrypted backup on a second installation and verify conversations, memory, promises, letters, journeys, and tree history.
- Verify requested reminders with actual notification/exact-alarm permissions, reboot, screen lock, and HyperOS battery restrictions.
- Verify a reply survives leaving the app and interrupted replies recover after an actual process kill.
- Action receipts are presently held in memory; after process restart, persisted app state is authoritative. There is no durable exactly-once action journal spanning process death.
- Local encrypted data survives ordinary restarts and compatible updates. Device loss, uninstalling, or lost encryption/signing credentials require a usable exported backup.
- Autonomous actions are limited to the implemented action schema. The installed AI cannot modify Kotlin code, rebuild its APK, or independently repair arbitrary software defects.

CI outcome and any remaining failures should be checked on the pull request before merge. Model behavior and device-specific checks must not be reported as passed without running them.
