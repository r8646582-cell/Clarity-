# UPDATE 15 — built for a lifetime

Umair: "What I know is crowded. Is it sustainable long term? This app will be with me for life, so make every
part of it long-term so it won't crash." After one day, About you already has 19 entries, many long; some are
still in third person ("he", "himself"); one mixes up two different apps ("a blocker app he named Purpose").

**First:** complete UPDATE-1 to UPDATE-14 if not done (UPDATE-12's "you" cleanup clearly hasn't run yet).
Overwrite prompt files (reflection updated, chapter new); merge CLAUDE.md and DESIGN.md.

## 1. Never lose data
CLAUDE.md "Built for a lifetime" part 1. Remove any destructive migration fallback FIRST, before any other
schema change in this update. Add migration tests. Add "Export my life". Test a full restore.

## 2. Layered memory
Part 2: caps, life chapters (`prompts/chapter.md`), archive. Run memory gardening now with the new caps and the
"you" rewrite, so his current What I know is trimmed and consistent.

## 3. Fixed-size context with relevant memories
Part 3: FTS search, "Possibly relevant from the past", 12k-token budget.

## 4. Speed with years of data
Part 4: indexes, Paging 3, the 5-year fake-data test in a separate database.

## 5. What I know redesign
DESIGN.md "What I know (calm, even after years)": collapsed sections with counts, 2-line items, top 5 + Show all,
future self as one paragraph, Chapters row.

## 6. Report
Tell Umair in plain words: that his data is protected across updates, how big his memory is now, and how the
app will behave after years (same speed, same cost per message).
