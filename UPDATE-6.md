# UPDATE 6 — QA fixes and production readiness

Umair ran a QA session. Results: the persona, safety boundaries and memory are strong (don't weaken them).
Problems: Mirror shows a raw API error, the "thinking…" indicator never appeared, and Untangle mode drifted
into giving plans and scripts. Also, his QA testing put invented facts (a job, partner, sick father) and a
crisis test into his real memory. This update fixes all of that and adds what a production app needs.
Do the tasks in order, commit after each, and report in plain language.

**First:** complete UPDATE-1 to UPDATE-5 if not done. Overwrite prompt files; merge CLAUDE.md and DESIGN.md.
Updated prompts: `persona.md`, `mode_untangle.md`. New: `testbench.md`, `gardening.md`.

## 1. Mirror letter failure (critical)
- Use the error log (task 4) to find the real cause. Likely suspects, check all: OkHttp default 10s read
  timeout on long non-streaming Pro calls; wrong model id or thinking parameter for the Pro model; request
  too large; JSON-mode flag sent on letters (letters are plain text).
- Apply CLAUDE.md "Reliability": timeouts, retries, preconditions, and the quiet delayed state on Mirror.
Done when: a test weekly letter (Developer menu) is written successfully, and a forced failure (airplane
mode) shows only "This week's letter is delayed." with Try again.

## 2. Thinking indicator and routing check
Implement CLAUDE.md "Waiting indicator" and the message "Details" sheet. Then verify the router: send the
heavy test message and confirm Details shows Deep, thinking on. If routing wasn't working, fix it.

## 3. Untangle mode
The new `mode_untangle.md` forbids plans, scripts and actions until the knot is untangled. Confirm mode
instructions are injected when untangle is active (they may not have been, which would explain the drift).

## 4. Error log, Off the record, Past conversations, Forget, Erase everything
Implement those CLAUDE.md sections. Add provenance (`sourceSessionIds`) with a safe migration.
Then tell Umair how to clean his memory: "Erase everything", or forget the QA conversations one by one.

## 5. Help numbers in context
Per CLAUDE.md. Prefill Pakistan defaults, marked "please verify," editable.

## 6. Test bench
Per CLAUDE.md. This replaces manual QA sessions: Umair runs it after each prompt or model change and pastes
the report.

## 7. Memory gardening, cost guard, automatic backup
Per CLAUDE.md.

## 8. Release builds
Per CLAUDE.md: signed release APK via GitHub Actions on version tags, installable from his phone, with
plain instructions for adding the signing secrets on GitHub.
