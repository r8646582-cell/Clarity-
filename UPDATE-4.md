# UPDATE 4 — what the best coaching apps do, done better

Based on a look at Mark Manson's Purpose app (onboarding snapshot, quests, journey log, voice), Rosebud
(guided programs, check-ins, weekly reports), Mindsera (thinking frameworks), Rocky.ai and Yoodli
(role-play practice). Do the tasks in order, commit after each, and explain changes to Umair in plain language.

**First:** complete UPDATE-1, 2 and 3 if they aren't done. Where files differ, this update wins.
Overwrite the prompt files; merge CLAUDE.md and DESIGN.md with your existing versions.

New prompt files: `snapshot.md`, `journeys.md`, `mode_onboarding.md`, `mode_journey.md`,
`mode_practice.md`, `mode_decision.md`, `mode_untangle.md`. Updated: `persona.md`.

## 1. Data model
Add `Snapshot`, `OnboardingStep`, `Journey`, `Pulse` per CLAUDE.md, with a migration that keeps all data.
Rename the Promises tab to **Path**.

## 2. Modes
Implement CLAUDE.md "Modes and tools": the "+" sheet, mode sessions, mode instructions injected after the
stable context, the `[[mode: …]]` line (hidden while streaming, parsed after), the mode header line, and
in-character styling for practice mode (DESIGN.md).
Done when: each of the four tools starts the right mode, and agreeing to a suggested practice in chat
switches into it.

## 3. Onboarding and snapshot
Implement CLAUDE.md "Onboarding and snapshot" and the DESIGN.md onboarding screens: values sort, IPIP-50
Big Five (verified items), the five onboarding conversations as Talk cards, then the snapshot.
Offer onboarding to the existing user too (he's already been using the app): show the first card on his next
visit, using what's already known as context.
Done when: finishing all steps produces a snapshot readable from What I know, and the snapshot appears in
the chat context block.

## 4. Journeys
Parse `journeys.md`, build the Path tab journey section and the daily journey card on Talk. One step per
calendar day, one active journey at a time.
Done when: starting "Break the avoidance loop" shows day 1 on Talk, the session runs in journey mode, and
day 2 appears the next day.

## 5. Daily pulse
Optional (Settings, off by default). Talk card once a day, saved to `Pulse`, included in context and in the
record lines (CLAUDE.md).

## 6. Safety net
Implement CLAUDE.md "Safety net" with the Get Help card. Add unit tests for the phrase matcher (English and
Roman Urdu, case-insensitive, word boundaries).

## 7. Read aloud
Implement CLAUDE.md "Read aloud": a setting plus a speaker button on each coach message.
