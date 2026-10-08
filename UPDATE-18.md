# UPDATE 18 — the growth tree and adaptive journeys

Umair wants a living picture of his real growth: habits built, bad habits unlearned, real accomplishments,
inner growth and relationships, across his whole life. It must be REAL: every leaf evidence-based, proposed
by the AI only after careful consideration over time, accepted by him. It should be dynamic and genuinely
beautiful. Journeys should also adapt to how he's actually doing instead of following a fixed plan.

**First:** complete UPDATE-1 to UPDATE-17 if not done. Overwrite prompt files (new: milestone, journey_adapt;
updated: reflection with `action_key`, mode_journey); merge CLAUDE.md and DESIGN.md.

## 1. Data and evidence
`Milestone`, `Branch`, `JourneyAdjustment` tables (with migrations and tests, per UPDATE-15).
Add `actionKey` to Promise from reflection. Implement the evidence stats in code (CLAUDE.md "Growth tree").
Unit-test the stats: 21-day consistency, 30-day absence while active, and that absence of data never counts.

## 2. Milestone proposals
Weekly job with `prompts/milestone.md`, the Talk card, accept / not yet / decline, monthly cap, no
re-proposals. Mention new proposals at the end of the weekly letter.

## 3. The tree
Build the Growth tree exactly as described in DESIGN.md: roots from his values, ridgeline ground, trunk with
chapter rings, area branches that appear with their first leaf, sub-branches, leaf shapes by type, deterministic
layout, zoom and pan, leaf sheet, timeline toggle, growing animation, empty state, Path preview.
Take real care with the drawing: smooth tapered strokes, natural curves, balanced composition, no clutter.
Add a Developer button "Preview tree with sample leaves" (fake data, not saved) so it can be judged with
10, 50 and 300 leaves.

## 4. Adaptive journeys
`prompts/journey_adapt.md` after each journey session; apply changes, store history, show the reason in Path,
pass it to the next journey session, pause/resume.

Done when: with sample data the tree looks beautiful at every size; a real proposal only appears with real
evidence; accepting grows a leaf with the animation; and a journey adjusts after a missed or failed day.
