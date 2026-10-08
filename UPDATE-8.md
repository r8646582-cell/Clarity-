# UPDATE 8 — premium polish pass

A UI review asked for more polish. Some ideas fit Purpose's design and are now in DESIGN.md
("Polish pass"); some were deliberately left out (glass blur on the input bar, pill-shaped tab indicators,
glows, pulsing placeholder text) because they'd make Purpose look like every other app.

**First:** complete UPDATE-1 to UPDATE-7 if not done. Merge DESIGN.md with your existing version.

## 1. Audit against DESIGN.md
Go screen by screen and compare what's built with DESIGN.md: colors, fonts, sizes, line heights, spacing,
radii, and empty states. List every mismatch you find and fix it. The review saw tight line height in coach
replies, which suggests the type styles aren't fully applied.

## 2. Polish pass
Implement every item in DESIGN.md "Polish pass": reading comfort, input bar alignment and hairline,
haptics, thinking dots, message entry animation, tab cross-fade, What I know spacing and tags, ridgeline in
empty states.

## 3. Check with the hardest cases
Largest system font, smallest supported screen, a 6-paragraph coach reply, keyboard open, Night and Day
themes. Nothing clipped, overlapping or unreadable.

Report the mismatches you fixed as a plain list for Umair.
