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
