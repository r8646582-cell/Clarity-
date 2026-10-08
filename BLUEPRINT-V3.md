# Purpose: Blueprint V3 (instructions for Claude Code)

Read this whole file, then CLAUDE.md, DESIGN.md and CHANGES-v2.md, before touching code. Work one phase at a time. Finish a phase (build green, tests green, acceptance criteria met) before starting the next. Commit after each phase.

## The product, in one sentence
A personal AI coach that builds a persistent, evidence-graded model of Umair, understands his values and contradictions, remembers his history, challenges his rationalizations when earned, teaches useful principles, and helps him deliberately become a better person.

## Non-negotiable rules
1. Only Umair's own words and actions are evidence about him. Coach-originated claims must never raise confidence or times-seen.
2. A conversation happening is not the same as a thing happening. A journey day, promise or step is complete only when he did it, or the coach explicitly records it via its action. Hunt for other "talked about it = done" bugs (for example "Kept: Promise" evidence titles in ActionExecutor).
3. Quotes shown as his words must be verified verbatim in code (memory/QuoteCheck.kt).
4. Crisis/safety features were removed on purpose by the owner. Do not re-add them.
5. English only. Real names stay (no redaction).
6. Keep the prompt contract: PromptContractTest and PromptValidation must pass. persona.md must contain "receipt", "success", "hypoth", "do not force". Each prompt keeps its original {{PLACEHOLDER}} set. No `[[action:` or `[[promise:` syntax, no epoch-ms fields.
7. Never change the DB schema without a proper Room migration and a MigrationTest. Settings columns helpNumbers and helpNumbersEdited stay as dead columns.
8. Privacy: data stays encrypted (SQLCipher). Nothing new leaves the device except the existing model API calls. No new analytics.
9. Every behavior bug the owner reports becomes a scenario in app/src/main/assets/prompts/testbench.md (needs Mode/User/Expect) and, where it is code logic, a unit test.

## Phase 0: make it build and prove it
The V2 changes were made without a full compile. First:
- Run `./gradlew testDebugUnitTest assembleDebug`. Fix every compile error and failing test. Likely spots: places that used the removed SafetyNet/HelpNumbers, any `Settings(...)` or `MemoryRepository(...)` constructor call sites, unused-import warnings.
- Verify on device or emulator: start a journey, defer day 1 ("start tomorrow"), confirm day 1 is NOT marked complete. Then do a real step and confirm the coach's advance_journey completes it.
- Risk to check: if the coach rarely emits advance_journey, journeys stall. If so, add a manual "Mark today's step done" control on the Path screen and make the coach confirm it.
- Acceptance: green build, green tests, journey behavior verified, a short note of what you fixed appended to CHANGES-v2.md.

## Phase 1: the scoreboard (build this first, it makes everything else measurable)
Goal: know whether a change made the coach better or just different.
- Offline eval harness (Gradle JVM task or a script in /tools, not shipped in the APK) that runs the 44 testbench.md scenarios against a model and grades each Expect line with an LLM judge. Output a table: scenario, pass/fail, reason. Store results in /eval/results/<date>.json and diff against the previous run.
- Memory exam: a fixed synthetic user history (invented, clearly fake) plus ~40 questions ("what did he promise last week?", "which value conflicts with his schedule?", "what no longer holds?"). Score retrieval and answers. Include trap questions where the coach must say "I don't know" and cases where an old fact was superseded.
- Honesty checks computed in code, not by judge: quote verification rate, share of notes whose confidence rose without a user-sourced evidence row, count of "done" states without a user-sourced event.
- Acceptance: one command prints a score; running it before and after a prompt change shows the difference; results are committed.

## Phase 2: better retrieval and time-aware facts
Current weakness: FTS fetches the newest 60 matches, STOP words include meaningful words (feel, want, need, think), and there is no notion of when a fact stopped being true.
- Hybrid retrieval: FTS4 plus on-device embeddings (small multilingual-free English model such as MiniLM via ONNX/TFLite; check size and licence, keep the APK reasonable). Merge results by reciprocal rank fusion, then rerank by recency, evidence grade and relevance to the current message.
- Embeddings are finders and flaggers only: find related notes, flag likely duplicates, flag possible contradictions. They never write memory text and never change confidence.
- Bitemporal facts: for notes and profile entries store valid_from, valid_to (when it was true) separately from recorded_at. Retire by closing valid_to, never by deleting. The context block should show "used to be true, changed on <date>" only when the change itself matters.
- Remove the stale Roman Urdu stop words in memory/RelevantMemories.kt and the "ruko|bas" regex in TalkCopy; reconsider the English STOP list using the memory exam.
- Acceptance: memory exam score improves versus Phase 1 baseline; migration test passes; no latency regression above ~150 ms per message on a mid-range device.

## Phase 3: ledgers computed in code
- Values-versus-behavior ledger: for each value he named, compute from data (promises kept by action_key, behavior events, later phone data) how his weeks compare, and feed a compact summary into the context block and weekly/monthly letters. The model describes the gap; code computes it.
- Disagreement ledger: dedicated table for "You and I see this differently" items (claim, his position, date, resolved/not), instead of free-text threads only.
- Contradiction register: pairs of his own statements that do not fit, with both quotes, status open/explained.
- Durable action journal: every coach action (record_promise, advance_journey, store_memory, etc.) logged with turn id, so a failed or duplicated action can be replayed or audited.
- Acceptance: unit tests for each ledger; the letter prompts receive the computed numbers; nothing in the ledgers comes from coach-only statements.

## Phase 4: objective behavior data (opt-in)
- Optional, off by default, explained plainly in Settings. Android UsageStatsManager (screen time, app categories), optionally calendar and sleep from Health Connect.
- Use only to ground say-do gaps ("you said phone away at 5; screen time shows 2h at 5-7"). The user can view, export and delete all of it. Stored locally, encrypted.
- Acceptance: permission flow works, data appears in the values ledger, deleting it removes it everywhere.

## Phase 5: stronger model for slow jobs
- Reflection, weekly/monthly letters, gardening and snapshot are written rarely and shape memory for years. Make the model per job configurable and default these to the strongest available model (the Anthropic client already exists as backup). Chat stays on the fast/deep DeepSeek routing for cost.
- Use the Phase 1 scoreboard to confirm the change helps before making it the default.
- Acceptance: per-job model setting, cost estimate shown in Settings, scoreboard comparison recorded.

## Phase 6: housekeeping
- PBKDF2 iterations (210k now) up to the current OWASP recommendation with a migration path for existing databases.
- Doc drift: README, CLAUDE.md and DESIGN.md must match the code. Historical UPDATE-*.md and docs/* stay as history.
- Offline prompt tuning (optional, only after the scoreboard exists): try variants of the non-persona prompts (reflection, gardening, letters) and keep the best by score. Persona changes stay human-reviewed.

## Definition of done for every phase
Build green, all unit tests green, scoreboard score not lower than before, new behavior covered by a test or testbench scenario, CHANGES-v2.md (or a new CHANGES file) updated, one commit per phase.

## Honest limits to remember
Prompts are near their ceiling; the gains now come from evidence quality, retrieval and measurement. Growth ultimately depends on Umair's own actions: the app can show him the gap and hold him to his word, not make the changes for him.
