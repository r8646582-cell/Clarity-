# UPDATE 12 — flow bugs and a full logic audit

Umair found a flow bug: in "Getting to know you", step 1 (story) never ended. The coach moved on to step 2's
questions in the same chat, he answered them, but only step 1 was marked done, so step 2 asked him again.
Also: the ↓ button sits on top of his message bubbles, and What I know still says "his mother and father".

He asked: "Find more things like this in the code and fix them too." That's the main job of this update.

**First:** complete UPDATE-1 to UPDATE-11 if not done. Overwrite prompt files; merge CLAUDE.md and DESIGN.md.
Updated prompts: mode_onboarding (explicit step end), reflection (`onboarding_covered`), gardening (profile).

## 1. Onboarding step completion
Implement CLAUDE.md "Step completion", including the one-time fix for his existing sessions.
Done when: finishing a step shows the "done / Next" card, the count goes up, and talking about a later
step's topic early also counts it.

## 2. ↓ button position and the "you" cleanup
DESIGN.md (centered ↓ button) and CLAUDE.md "One-time memory cleanup".

## 3. Full logic audit (the important part)
This bug is a "state that doesn't follow the conversation" bug. Hunt for every bug of that kind. Read the
code for each flow below, write down how it could go wrong, fix it, and add a test for each fix.

**Modes and sessions**
- Every mode (onboarding, journey, practice, decision, untangle) has a clear way to end: by the coach's hidden
  line, by "End", by "New conversation", and by the session timing out. Check all four for each mode.
- A mode never leaks into the next session; a new session never inherits a stale mode, header or card.
- Continuing an old conversation restores its correct mode, or none if that mode already ended.
- Off-the-record sessions never touch memory, letters, promises with reminders, journeys or onboarding.

**Journeys**
- Day advances exactly once per calendar day, only after a real journey session; never twice if he opens two
  journey chats in one day; never skips a day; handles him missing several days (resume, don't reset).
- Starting a new journey properly ends the old one; finishing day 7 marks it done exactly once.

**Promises and reminders**
- No duplicate promises from the hidden line plus reflection, or from continuing a conversation.
- Undo on "Promise saved" also cancels the reminder. Editing a due time reschedules it; deleting cancels it.
- A reminder time already in the past (e.g. agreed at 9:05 for 9:00): fire now or ask; never silently drop it.
- Due "today/tonight/tomorrow" resolves correctly around midnight.

**Hidden lines**
- `[[promise]]`, `[[mode]]`, `[[practice_with]]`, `[[step_done]]`: parsed even with extra spaces, different
  case, or a line break inside; never shown even briefly; multiple lines in one reply all handled;
  a malformed line is hidden and logged, not shown.

**Sending and streaming**
- Double-tapping send can't send twice. Sending while a reply is streaming is blocked (or queued) clearly.
- Switching conversations, tabs or modes while a reply streams never puts the reply in the wrong chat.
- Retry after a failure never duplicates his message.

**Time and dates**
- Weekly letter "Monday to Sunday" and monthly periods use local time (Pakistan, UTC+5) consistently,
  including sessions that cross midnight.
- Session "new day" ending works across midnight without splitting a conversation in the middle of a reply.

**Letters, reflection, snapshot**
- Reflection never runs twice for the same messages (check the reflected-up-to marker everywhere).
- Letters are never written twice for the same period, even if the app restarts mid-job.
- Snapshot regenerates only when asked or when all steps finish; the step count never exceeds 5.

**Screens**
- Every list updates live from the database (Path, What I know, Mirror, Promises, drawer) with no need
  to leave and come back.
- Nothing ever overlaps text it shouldn't (floating buttons, cards, snackbars above the input bar).
- Every screen's back button does the obvious thing.

## 4. Report
Give Umair a plain list: each bug found, what would have happened to him, and that it's fixed.
