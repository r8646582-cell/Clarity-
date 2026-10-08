# UPDATE 14 — coach fixes from the test bench

Test bench findings (most replies were strong; these were the recurring problems):
- Several questions in one reply (landing an action, untangle, decision, practice, journey, diagnosis, exam).
- Invented facts with an EMPTY context: "abbu's comparisons, the cousin's result, three months of missed days"
  (leaked from the voice examples).
- A numbered list in practice setup; mild swearing; a reply opening with a bare "No" to "do I have ADHD?"
- An invented journey name in `[[mode: journey | name: Quieting Anxiety]]`.
- An exam-fear reply said "October" for a February exam and ran to six paragraphs.

Prompt fixes are in persona, examples, mode_practice, mode_decision, mode_untangle; testbench has 3 new
scenarios. Umair can paste these through the Prompt editor, or:

## Tasks
1. Overwrite those prompt files (warn Umair if he has Prompt editor overrides for them).
2. Add "Available journeys" to the context block and validate journey names in `[[mode: journey …]]`
   (CLAUDE.md). An unknown name opens the journey list instead of starting anything.
3. Test bench: also count question marks in each reply and show "questions: N" next to it, highlighted
   when N > 1, so this problem is visible at a glance.
