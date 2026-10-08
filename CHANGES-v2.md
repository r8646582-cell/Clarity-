# Purpose v2: what changed (nothing here has been built on a device)

## Removed
- All crisis/safety code: SafetyNet, Help numbers screen and settings, help card in Talk, crisis routing, helplines resource, related tests.
- Roman Urdu routing words. Persona and prompts are English only.
- Settings columns helpNumbers/helpNumbersEdited are kept as dead columns so the DB schema and migrations are unchanged.

## Bugs fixed
- A journey day no longer completes because you sent 3 messages. Only the coach's advance_journey action completes a day, and the prompts say not to emit it when you deferred or skipped.
- Journey opening no longer says "yesterday".
- The coach can no longer reinforce its own memory: repeating an insight no longer raises confidence or times-seen.
- Quotes saved as "his words" are checked in code against what you actually wrote (QuoteCheck).

## Prompts (all 18 rewritten)
Persona: mission, memory graded by evidence, values vs behavior, earned challenges, holding ground, teaching one principle at a time, how to start modes. 17 new examples. Reflection, gardening, letters, snapshot, chapter, all mode prompts and journey prompts improved. 3 new journeys (10 total). Test bench is 44 scenarios.

## Verified here
- QuoteCheck and ModelRouter compiled with Kotlin 2.0.21 and their tests pass (14/14).
- Prompt placeholders match the originals; no forbidden syntax; no leftover crisis references.

## Not verified
- The full Android build and all other tests. Run: ./gradlew testDebugUnitTest assembleDebug
- On device: do journey days still advance when you actually do the step?

## Phase 1: the scoreboard (Blueprint V3)
Added `tools/eval/` (offline Python harness, not in the APK) and `eval/results/`. One command, `python3 tools/eval/score.py`,
prints a score from: the 44 test-bench scenarios graded by an LLM judge, a memory exam over an invented history (43 questions
incl. superseded-fact and "I don't know" traps; answers + retrieval coverage), and honesty checks computed in code (quote
verification, risen notes without his evidence, done states without his event). Results are saved per run and diffed against the
previous comparable run. See `tools/eval/README.md`.
Baseline committed in `eval/results/` covers the code-computed sections only (retrieval coverage 70.8 overall / 58% archive-only,
honesty 100 on the synthetic fixture). The model-graded sections (bench, answers) need an API key and have NOT been run yet.
Phase 0 (a full `./gradlew testDebugUnitTest assembleDebug` and the on-device journey check) has not been done in this environment.

## Phase 2 (started): stale Roman Urdu removed
Removed the Roman Urdu stop words from `RelevantMemories.STOP` and `ruko|bas` from the practice-mode stop regex in `TalkCopy`.
Memory exam retrieval is unchanged (70.8). Dropping feel/want/need/think/know from STOP scored slightly worse (70.8 -> 69.9), so they stay.
NOT done yet: hybrid embeddings retrieval, bitemporal facts (valid_from/valid_to + migration). Not compiled here: the Android toolchain
cannot be downloaded in this environment, so the two Kotlin edits above are untested by Gradle.

## Phase 0 (BLUEPRINT-V3)
- CI run on GitHub (`./gradlew :app:testDebugUnitTest`, then `:app:assembleRelease`): both green on the V2 code with no fixes needed. No compile errors from the removed SafetyNet/HelpNumbers, `Settings(...)` or `MemoryRepository(...)` call sites.
- CI now runs on every branch push and cancels superseded runs; the emulator smoke job runs only on main or by hand.
- Journey deferral checked in code: ending a conversation never completes a day, only `advance_journey` does, the prompts forbid emitting it when he defers, and a testbench scenario covers it.
- Added a manual "I did it" action on the Path screen as the stall fallback. It uses the same one-step-a-day rule as `advance_journey`, and the coach's next context line says today's step is done.
- Still needs a device: defer day 1 and confirm it stays open, then do a real step and confirm the coach completes it.
- Smoke test fix: the Android 35 emulator job failed with `adb: device offline` (no app crash in the log). `smoke.sh` now waits for the device and retries the "is it running" check.

## Phase 2 (continued): bitemporal facts and hybrid-retrieval building blocks
- DB 10 → 11 (`MIGRATION_10_11`, `MigrationTest`): `note` and `profile_entry` gain `recordedAt`, `validFrom`, `validTo` (null = still true). Backfilled from `firstSeen`/`updatedAt`; retired or resolved rows get `validTo` = when last seen. Only adds.
- Retiring now closes `validTo` and never deletes (`Note.retiredAt`, used by gardening, caps, duplicate cleanup, replacement and reflection's resolve). The first close wins. Archive search lines for set-aside notes are dated by `validTo` ("an older note, set aside then").
- `memory/HybridRetrieval.kt`: reciprocal rank fusion, recency/evidence rerank, cosine, nearest and duplicate flagging, plus an `Embedder` interface. Pure, tested, and nothing writes memory text or confidence from it.
- NOT done: no embedding model is bundled or wired in (model choice, size and licence still to check), so retrieval is still FTS-only in the app and the memory exam score is unchanged. A changed profile value still overwrites the old one (the old value is not kept as history yet). `schemas/11.json` was written by hand; the first Gradle build regenerates it with the real identity hash, so commit that file. Nothing here was compiled by Gradle (no Android SDK in this environment); CI is the check.

## Phase 2 (continued): on-device embeddings wired in (model files still to add)
- DB 11 → 12 (`MIGRATION_11_12`): `embedding_row` (one vector per archive search document, inside the encrypted DB; derived, can be dropped and recomputed).
- `OnnxEmbedder` (all-MiniLM-L6-v2 through ONNX Runtime 1.19.2 Android), `WordPieceTokenizer` (BERT uncased), `EmbeddingIndex` (background sync of vectors, nearest search with an in-memory cache). `SearchIndex.relevant()` now merges full-text and meaning matches with reciprocal rank fusion plus a recency rerank (`RelevantMemories.rankHybrid`). With no model, or no meaning matches, the result is exactly the old ranking.
- The model files are NOT in the repo yet: add `app/src/main/assets/embedding/model.onnx` and `vocab.txt` (see `docs/EMBEDDING_MODEL.md`) and the Apache-2.0 licence text to `licenses/`. Until then the app behaves as before.
- Still unmeasured: the memory exam's Python harness does not run the ONNX model, so the retrieval score gain and the ~150 ms latency budget need a run on a device. Nothing here was compiled by Gradle in this environment.

## Phase 2 (continued): measured, tuned and hardened
- Model files added (all-MiniLM-L6-v2, int8, 23 MB; vocab 30,522 lines; Apache-2.0 text in `licenses/`). Checked with ONNX Runtime outside the app: inputs `input_ids`/`attention_mask`/`token_type_ids`, output `[1, tokens, 384]`, related sentences score about 0.5 and unrelated about 0.
- Scoreboard: `--retriever hybrid` (tools/eval) runs the same fusion offline. Memory exam retrieval coverage 70.8 -> 80.6 (archive-only 58% -> 72%), honesty unchanged at 100. The similarity cut-off moved 0.45 -> 0.30 on that evidence (0.45 gave only 72.2). The exam is small and synthetic, so the number shows direction, not an exact gain.
- Stability: embedding sync is serialised and runs off the main thread; the model is warmed up at launch after everything else; any embedding failure falls back to the old full-text result; the `.onnx` asset is stored uncompressed.
- Speed: embedding one message took about 2.4 ms on a desktop CPU (ONNX Runtime, 1 thread-pool default); a phone is several times slower, so expect tens of milliseconds. Still to confirm on a device against the 150 ms budget.

## Phase 2 (continued): live-note embeddings and profile history
- **Stability fix to my earlier change:** writers (`indexArchive`, `rebuild`, `indexSession`, letters, chapters) are called inside database transactions and the restore/erase writer lock, and I had made them embed documents there. Embedding now never runs in a transaction: writers call `EmbeddingIndex.requestSync()`, a single background worker (conflated, 1.5 s debounce so the commit lands first) does the work, a round is capped at 200 documents and re-requests itself until done. Vectors made from older text are ignored until refreshed.
- **Live notes get vectors too** (`live_note` kind in `embedding_row`). `MemoryRepository.relevantInsights` merges keyword and meaning matches among active notes (`RelevantMemories.rankNotesHybrid`: rank fusion plus recency and evidence-grade nudge). Retired/deleted notes never come back through meaning. On the synthetic memory exam this changed nothing (82.0 at 0.25 before and after): its notes already sit in the stable context. It matters once he has more notes than the context block holds.
- **Profile history, no new table:** when reflection changes what a profile line says, the old words are saved as a retired note ("Used to be true, city: Karachi") with `validFrom` = when that line began and `validTo` = the change. It is searchable, shown as "used to be true, changed then", and travels with backup, restore, forget and erase like any note. Never kept for his own edits, deletions or confirmed values, so a correction is not treated as history. Copies the line's source conversations, so forgetting a conversation forgets its history.
- Tests: `ProfileHistoryTest`, `LiveNoteRankingTest`; the eval mirror gained note ranking. Not compiled by Gradle here; CI is the check.
