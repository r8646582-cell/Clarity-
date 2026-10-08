# UPDATE 3 — action and learning how he works

Purpose now pushes for real action (small, specific, agreed promises with follow-up) and builds a detailed
picture of how Umair behaves: triggers, chains, cycles, what works for him, and his say-do record.
Do the tasks in order, commit after each, and tell Umair in plain language what changed.

**First:** finish UPDATE-1.md and UPDATE-2.md if they aren't done. Where files differ, this update wins.
Merge CLAUDE.md and DESIGN.md with your existing versions; overwrite the prompt files.

## 1. Data model
Add/extend per CLAUDE.md: `Promise` (why, dueAt, remindAt, whatHappened, lesson, area), `BehaviorEvent`,
`Strength`. Write a Room migration that keeps all existing data.

## 2. Promises saved during chat
Implement CLAUDE.md "Promises saved during chat": hide the `[[promise: …]]` line while streaming, parse
it when the reply completes, save the promise, show the inline "Promise saved" line, and schedule the
reminder. Add `now` to runtime flags.
Done when: agreeing to an action in chat creates the promise instantly, the hidden line never appears on
screen, and the reminder notification fires at the right time (also after a phone restart).

## 3. Reflection
Parse the new reflection fields (`behavior_events`, `strengths`, kept/broken objects with
`what_happened` and `lesson`) and save them. Don't create duplicate promises.

## 4. What the record shows
Compute the stats described in CLAUDE.md in code and add them to the chat context block and to letter
inputs ({{RECORD}}, {{BEHAVIOR_EVENTS}}). Include unit tests for the stats.

## 5. Context block
Add strengths, the last 15 behavior events, all open promises, recent kept/broken promises with lessons,
and the record lines, per CLAUDE.md Core flow 1. Keep it stable within a session.

## 6. Screens
Update Promises, What I know, and the Talk inline confirmation per DESIGN.md.
Done when: after a few real conversations, What I know shows patterns as chains, what works for him with
evidence, and his strengths; Promises shows the why, reminders, and lessons.
