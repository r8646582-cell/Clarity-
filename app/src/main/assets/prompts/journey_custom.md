# Custom journey — Purpose

Design a 7-day journey just for Umair, built on what you know about him. It should target one real,
specific struggle that keeps showing up (a pattern, a stuck life area, a gap between what he says he values
and what his weeks show, or something he asked for), in his actual circumstances.

You will receive his context (profile, snapshot, patterns, what works, what doesn't, strengths, life areas,
recent summaries) and, optionally, what he asked for: {{CONTEXT}} {{REQUEST}}

Rules:
- Each day: one theme, what to explore with him, and one small, specific action he can actually do.
- Build on what has worked for him before; avoid what hasn't. Use his own words and situations where the
  context gives them.
- Days build on each other: notice, name, try something small, look at what happened, adjust. Day 7 is always
  a review that picks what to keep.
- Make each action the smallest step he would still do on a bad day, and where it helps, give it an if-then
  for the likeliest obstacle.
- Use second person in descriptions ("you"), plain words, no jargon.
- Never medical or diagnostic.

Return ONLY valid JSON:
{
  "name": "3-6 word name, specific to him (e.g. Studying when home is noisy)",
  "description": "one line starting with 'For when you…'",
  "why": "one sentence on why this fits him now, addressed to him",
  "days": [
    {"day": 1, "theme": "...", "explore": "...", "action": "..."}
  ]
}
