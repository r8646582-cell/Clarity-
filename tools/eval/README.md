# Purpose scoreboard (Blueprint V3, Phase 1)

Know whether a change made the coach better or just different. Offline, standard-library Python 3.9+, not part of the APK.

```
python3 tools/eval/score.py                      # run everything that can run, print one score, save + diff
python3 tools/eval/score.py --only honesty,retrieval      # code-computed sections only, no key needed
python3 tools/eval/score.py diff eval/results/A.json eval/results/B.json
python3 -m unittest discover -s tools/eval/tests          # harness self-tests, no network
```

Model-graded sections need a key: `export PURPOSE_EVAL_API_KEY=...` (DeepSeek by default, `--base-url`/`--model` to change;
`--provider anthropic` works too). Use `--judge-provider/--judge-model/--judge-api-key-env` for a stronger judge than the
coach under test. Without a key the command still runs and prints the sections computed in code, and says it is partial.
`--provider fake` exercises the plumbing only and its numbers mean nothing.

## What is scored

| Section | How | Weight |
|---|---|---|
| **bench**: the 44 `testbench.md` scenarios | The coach model answers each scenario; an LLM judge grades every *Expect* line (pass/fail + reason). Request built like the app's bench: persona + examples, tool schema (read out of `AiConfig.kt`), empty-memory context, "Test setup", mode prompt, runtime flags, off the record. | 35 |
| **answers**: memory exam, ~43 questions | Fixed invented history (`fixtures/history.json`, "Tester Zed", clearly fake) and questions (`fixtures/memory_exam.json`) of five kinds: recall, time/promises, synthesis, **superseded** facts (current fact must win), **trap** questions (must say "I don't know"). The coach sees the stable context block plus what retrieval pulls in. | 25 |
| **retrieval**: same exam, no model | For each answerable question, share of its gold evidence the coach could see (stable context + FTS retrieval). Reports archive-only coverage separately. | 20 |
| **honesty**: computed in code, no judge | Quote verification rate (port of `QuoteCheck`); risen notes (confidence above guess or seen more than once) with no message from him in any cited session; "done" states (kept promises, completed journey days) with no event of his behind them. Runs on the synthetic fixture, or on your own *Export my life* / backup JSON with `--backup file.json`. | 20 |

OVERALL is the weighted mean of the sections that ran, and says which. Only runs that covered the same sections are compared.

## Results

Each run is saved to `eval/results/<date>.json` (config, git commit, hash of `prompts/*.md` and the fixtures, every
reply and verdict) and diffed against the previous comparable run: score deltas, scenarios/questions that started
failing or passing, retrieval coverage changes. Commit results with the change that caused them.

## Honest limits

- The retrieval and context rendering are **mirrors** of `RelevantMemories.kt` / `ContextFormatter.kt` (tests copy the
  Kotlin test cases to catch drift). When the Kotlin changes, change the mirror. Model routing (Fast vs Deep) is not
  mirrored: one model answers everything.
- Honesty "done without his event" is strict: a promise ticked by hand in the UI leaves no message, so it counts as
  unsupported until Phase 3's action journal records who did what. On the synthetic fixture all three checks are 100 by
  construction; their value is on a real export and as a regression guard (see `Honesty` tests for the failing cases).
- LLM-judged numbers are noisy by a scenario or two. Treat a one-scenario flip as a prompt to read the reply, not as proof.
- Memory exam gold answers depend on `fixtures/build_history.py`; regenerate with it and re-run, never edit `history.json` by hand.

## Phase 2: comparing the hybrid retriever

```
python3 -m venv /tmp/v && /tmp/v/bin/pip install onnxruntime numpy
/tmp/v/bin/python tools/eval/score.py --only retrieval,honesty --retriever hybrid [--min-similarity 0.30]
```

`--retriever hybrid` mirrors the app's `SearchIndex.relevant()` (full-text + MiniLM from `app/src/main/assets/embedding/`,
reciprocal rank fusion, recency rerank). On the synthetic exam: retrieval coverage 70.8 (full-text only) -> 80.6 at the app's
0.30 cut-off (83.3 at 0.20; it plateaus below 0.25). Saved as `eval/results/2026-10-08-hybrid-minilm.json`. The answers/bench
sections use the same retriever when it is selected.
