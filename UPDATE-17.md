# UPDATE 17 — cost efficiency for the long run

Umair wants running costs to stay low for decades. Thanks to UPDATE-15, the context size no longer grows
with years of use, so cost per message is already flat. This update squeezes it further and makes the cost
visible.

**First:** complete UPDATE-1 to UPDATE-16 if not done. Merge CLAUDE.md.

## Tasks
Implement CLAUDE.md "Cost efficiency (keep it cheap for decades)": per-feature cost breakdown and cache hit
rate, off-peak scheduling for background jobs (verify DeepSeek's current pricing first), no wasted calls,
right-sized routing, leaner prompts, output limits, and "Compare models".

Then report to Umair in plain numbers: average cost per message, per day, and per month at his current
usage, the cache hit rate, and what changed.
