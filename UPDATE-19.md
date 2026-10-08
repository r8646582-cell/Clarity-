# UPDATE 19 — getting time and facts right

Umair found three problems:
1. The home screen showed an AI-written opening line at 12:17am: "Good evening… How did Monday morning
   actually go?" It was Sunday; Monday hadn't happened. The line was written at the last reflection, without
   knowing when he'd next open the app, so it went stale.
2. In chat, the coach got dates and hours wrong, and when asked "what time did I say that?" it had no
   timestamps to look at.
3. It repeated a fact he had corrected before (the blocker app is Dechainer; Purpose is the coach's name).

The fix: the model never does date math; code computes all time facts. Corrections are permanent.
The home screen shows only things computed from the database.

**First:** complete UPDATE-1 to UPDATE-18 if not done. Overwrite prompt files (persona, reflection, testbench
updated); merge CLAUDE.md and DESIGN.md.

## Tasks
1. CLAUDE.md "Time grounding": `now` sentence, code-computed due phrases, message timestamps in the API
   payload, greeting bands with tests, remove `next_opening`.
2. DESIGN.md "Talk: home": greeting, Coming up, Pick up where you left off, one card.
3. CLAUDE.md "Corrections stick": apply corrections by id, protect confirmed entries, "confirmed" tag.
   Then fix his current data: the profile entry about "a blocker app named Purpose" must say Dechainer.
4. Run the two new test bench scenarios (time awareness, accepting a correction).
