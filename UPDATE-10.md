# UPDATE 10 — final: every QA bug, journeys upgraded, and self-maintenance

This is the last planned update. Umair ran the full Part A test (results below). Fix every item, then add
the tools that let him maintain the app on his own. Work in order, commit after each numbered task, run the
tests, and at the end give Umair a plain list of what was fixed and anything you couldn't fix.
Version this release **1.0.0**.

**First:** complete UPDATE-1 to UPDATE-9 if not done. Overwrite prompt files; merge CLAUDE.md and DESIGN.md.
Updated prompts: persona, reflection, gardening, mode_practice, journeys. New: journey_custom.

## 1. Deep replies failing (QA 4.1, 5.3, global "streaming errors")
"Couldn't finish that reply" happens often with thinking on, and Try again gets stuck on "thinking…".
Implement CLAUDE.md "Deep replies must not fail" fully (max_tokens, reasoning heartbeat, finish_reason,
automatic Fast fallback, no stuck states). Done when: 10 heavy messages in a row all complete.

## 2. Developer menu missing (QA 12.1)
Implement CLAUDE.md "Developer menu (visible)", then the Health check and Prompt editor sections.
Done when: Settings > Advanced > Developer opens, and the Test bench runs all scenarios.

## 3. Chat scrolling and ↓ button (QA 2.2, 2.6)
Implement the new DESIGN.md "Keyboard and scrolling (like Claude)": his message near the top, reply streams
below, stop following at the screen bottom, ↓ button whenever there's more below.

## 4. Haptics (QA 2.7, 4.4, 4.5, 11.6)
Implement CLAUDE.md "Haptics (QA: none worked)" with the Vibrator fallback. Verify on his Xiaomi.

## 5. System navigation bar light grey (QA 1.2, 1.3)
Per DESIGN.md color section. Check both gesture and 3-button navigation.

## 6. "Talk about this letter" opens a journey chat and soft-locks (QA 10.4)
It must always start a NEW session (normal mode, letter text in context), never reuse the active journey
session. Fix the root cause: keep one source of truth for the active session and its mode. "End" must
always work, in every mode, and "New conversation" must always be reachable as an escape. Add a test:
active journey → open letter → Talk about this letter → End → back to normal Talk.

## 7. Practice mode (QA 5.1, 5.2, 5.6)
- Header showed "talking with I need to practice…": never put his raw message in the header. The "+" sheet
  asks "Who's it with?" (short text field, max 30 characters, placeholder "e.g. Abbu"). If started from chat,
  read the name from the hidden `[[practice_with: …]]` line (parse and hide like other hidden lines).
  Fallback header: "Practicing a conversation".
- Replay offer and offering practice in normal chat are fixed in the updated prompts. Confirm the mode
  file is actually injected in practice sessions.

## 8. Big Five has political questions (QA 9.0)
The questionnaire asked about voting liberal vs conservative. That means the wrong item set was used (the
longer IPIP-NEO versions have political items). Use the IPIP Big-Five Factor Markers, 50 items, verified
from ipip.ori.org. Remove any item about politics or religion. If his saved answers include such items,
drop them and rescore.

## 9. Snapshot missing (QA 8.5)
- What I know always shows the "Your snapshot" row. Before it exists: "Your snapshot" / "Finish getting to
  know you (2 of 5)" with a Continue button, plus "Write it now with what I know" once at least 2 onboarding
  steps are done.
- Make sure the snapshot is generated with `prompts/snapshot.md` and shown in the letter-style view (a
  portrait, not a list of events).

## 10. What I know: "he/his" and duplicates (QA 1.3, global memory formatting and scalability)
The updated reflection and gardening prompts write to him as "you" and merge duplicate people. Run the
one-time gardening migration (CLAUDE.md "Memory at scale"), then implement the context budget and the
"Show all / search / Archived" layout.

## 11. Small UI fixes (QA 1.3, 4.5, 6.4, 7.4)
DESIGN.md "Small feedback": rename confirmation, Undo on swipes and on "Promise saved", help number labels
not truncated. Journey text "he's" → "you've" is fixed in journeys.md. Path tab updates instantly after a
journey step in chat (observe the database).

## 12. Robotic voice (QA 2.12)
CLAUDE.md "Read aloud: voices": voice picker, speed and pitch, link to better system voices.

## 13. Journeys upgraded
CLAUDE.md "Journeys, upgraded": completed status and takeaway, Suggested for you, Make one for me
(custom journeys), Yours and Completed groups.

## 14. Final checks
- Run the full test bench and fix anything that clearly fails because of code (not prompt taste).
- Run every item in FULL-TEST.md Part A that you can verify in code or tests.
- Make sure the GitHub Actions release build works and tag v1.0.0, so Umair can install it from his phone.
- Update README.md with: how to install from GitHub Releases, where prompts live, how to use the Prompt
  editor, the Developer menu, and MAINTENANCE.md.
