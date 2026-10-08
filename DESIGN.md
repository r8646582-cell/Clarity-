# DESIGN.md — Purpose

Read this before touching any UI. It replaces the default Material 3 look completely.

## Direction: "Hindu Kush at dusk"
Purpose is a quiet place to think, used mostly in the evening. The look comes from Umair's home, Chitral:
slate mountain dusk, snow, glacier light, and one warm accent from Chitral's apricots.
It should feel like a calm, well-made journal, not a tech product. Nothing flashy. One memorable moment
(the opening line on the Talk screen); everything else is quiet and disciplined.

Do NOT use: default Material purple/lavender, pill-shaped nav indicators, tonal surface tints,
cards with drop shadows, ALL-CAPS labels, emoji, gradients.

## Color tokens
Dark ("Night") is the default theme. Light ("Day") is offered in Settings, plus "Follow system".

| Token | Night | Day | Use |
|---|---|---|---|
| background | #1C2533 | #EEF1F2 | Screen background |
| surface | #263244 | #E1E6EA | User message bubble, sheets, input field |
| text | #E9ECEF | #1C2533 | Primary text |
| textMuted | #9AA8B8 | #5B6B7C | Secondary text, timestamps, hints |
| accent | #F0B35E | #A8661F | Apricot: active nav item, send button, kept promises, links |
| hairline | #E9ECEF at 10% | #1C2533 at 10% | Dividers, nav top border |
| danger | #E07A6B | #B4473A | Delete confirmations only |

Accent is used sparingly. If more than ~3 apricot things are on screen at once, remove some.
Turn off Material dynamic color. Set status bar and nav bar to the background color: use
`enableEdgeToEdge(SystemBarStyle.dark(transparent))` (light style for Day), set
`window.isNavigationBarContrastEnforced = false`, and draw the background behind the bars. Test with both
gesture navigation and 3-button navigation on his Xiaomi; the bottom bar must never be light grey.

## Typography
Bundle both fonts in `res/font` (both are free on Google Fonts).

- **Newsreader** (serif): Purpose's voice. Coach messages, the opening line, letters, screen titles, list items
  that are "his words" (promises, notes).
- **Hanken Grotesk** (sans): interface. User messages, buttons, labels, settings, timestamps.

| Style | Font | Size / line height | Weight |
|---|---|---|---|
| openingLine | Newsreader | 28 / 36 sp | 400 |
| screenTitle | Newsreader | 30 / 36 sp | 500 |
| coachBody | Newsreader | 18 / 28 sp | 400 |
| letterBody | Newsreader | 19 / 31 sp | 400 |
| itemText | Newsreader | 18 / 26 sp | 400 |
| userBody | Hanken Grotesk | 16 / 24 sp | 400 |
| label | Hanken Grotesk | 14 / 20 sp | 500 |
| meta | Hanken Grotesk | 13 / 18 sp | 400, textMuted |

Sentence case everywhere. Keep text lines under ~70 characters: side padding 24dp on phones.

## Shape, spacing, motion
- Spacing scale: 4, 8, 12, 16, 24, 32, 48 dp. Screen side padding 24dp.
- Radius: user bubble 20dp with the bottom-right corner at 6dp; input field 16dp; bottom sheets 24dp top
  corners; primary button fully rounded. Nothing else has rounded containers.
- No elevation or shadows anywhere. Separate things with space first, a hairline second.
- Motion: new messages fade in (150ms). The opening line fades in once per session (600ms). Bottom sheets
  slide up. Nothing else animates. Respect the system "remove animations" setting.

## Screens

### Talk (home)
New session, before he types:
```
 Purpose                                ⋯
 
 
 Good evening, Umair.                    meta, textMuted
 Last time you were wrestling with       openingLine, text
 whether you're enough. How's that
 sitting with you today?
 ╱╲__╱╲╱╲___                             ridgeline (see below)
 
 
 ( Just listen )                         chip, above input
 [ Talk to me…                  ] (↑)    input + apricot send
 ─────────────────────────────────────
  Talk   Mirror   Path   What I know
```
- (The AI-written opening line was removed; see "Talk: home".)
- **The ridgeline** is the one signature element: a single thin stroke (1.5dp, textMuted at 40%) drawn as
  a vector path of a mountain skyline, full width, under the opening line. It appears only on the
  new-session state and fades out when the first message is sent. Also use the same ridgeline as the app icon
  (apricot stroke on #1C2533) and splash.
- ☰ at the left of the header opens the conversations drawer (CLAUDE.md "Conversations").
- ⋯ menu items: New conversation, Off the record, Settings.
- Header: only "Purpose" (screenTitle, but 22sp) and a ⋯ overflow menu (items listed above). Remove the header toggle, "End" text and gear icon.
- "Just listen" becomes a small chip above the input. On: chip filled with surface, label "Just listening".

During a conversation:
- **Keyboard and scrolling (like Claude):** the input bar always sits directly above the keyboard
  (edge-to-edge with `imePadding()`), and the message list shrinks above it. When he sends a message, scroll
  so HIS message sits near the top of the visible area, and let the reply stream in below it. Follow the
  stream only until the reply reaches the bottom of the screen, then stop following, so he reads from the
  start instead of watching the end. Whenever there's content below the visible area, show a small round
  "↓" button that jumps to the latest message: 36dp, horizontally CENTERED, 12dp above the input area,
  surface color at 90% opacity with a hairline border. It disappears at the bottom. (Right-aligned, it sat on
  top of his message bubbles.)
- Coach messages: no bubble, full width, coachBody, text color. Paragraphs separated by 12dp.
- User messages: right-aligned bubble in surface color, userBody, max 80% width.
- 24dp between turns. A centered meta line ("Today", "Yesterday", date) when a new day starts.
- When a promise is saved from the chat, show one line under that coach reply, in meta style with a small
  accent check: "Promise saved" or "Promise saved, reminder at 9:00 pm". Tapping it opens Promises.
- While Purpose is replying: three small dots pulsing slowly in textMuted, left-aligned, then the streamed text.
- Input: surface-filled field (no outline), placeholder "Talk to me…", grows to 5 lines. Send button is an
  apricot circle with an arrow; disabled state textMuted.

### Talk: home (replaces the AI-written opening line)
A new conversation opens calm and correct, never with an AI guess that might be stale or wrong.
```
 ☰ Purpose                               ⋯


 Still up, Umair?                         openingLine style (time band greeting)
 What's on your mind?                     itemText, textMuted
 ╱╲__╱╲╱╲___                              ridgeline

 Coming up                                label, textMuted (only if something exists)
 Study 1 hour, Monday 8:00am              itemText row, meta "in 31 hours"
 Pick up where you left off                label, textMuted
 Planning the Monday start                itemText row, meta "last night"
 Break the avoidance loop, day 3          journey card (if any)
```
- Greeting by time band (CLAUDE.md "Time grounding").
- "Coming up": at most 2 rows: promises due in the next 48 hours, with code-computed time. Tap opens the promise.
- "Pick up where you left off": the most recent conversation if it was within 3 days and didn't clearly end.
  Tap continues it.
- Then at most one card (onboarding step, journey step, growth-tree proposal, pulse), as before.
- Everything here is computed from the database, never written by the AI, so it can't be wrong about time.
- Coach brings up anything personal only after he sends his first message, with the full context.

### Conversations drawer
- Slides in from the left, 85% screen width, background color with a hairline right edge, no shadow.
- Top: "New conversation" as a full-width row with a "+" icon in accent, then a surface-filled search field
  (placeholder "Search conversations").
- Group labels in label style, textMuted ("Today", "Previous 7 days", "September").
- Rows: title in itemText (one line, ellipsized), meta line with the time or date and the mode if any
  ("Practice", "Journey, day 3"). The open conversation's row has a 2dp accent bar on the left.
- Empty search: meta text "Nothing found."
- Opening a past conversation scrolls to its latest message, with the input ready to continue.

### Talk: cards, tools and modes
- **Cards** sit between the opening line and the input on a new session, max one at a time, in this
  priority: onboarding step, today's journey step, daily pulse. A card is a surface-colored block (radius
  16dp), itemText title, meta subtitle, and a text button. Dismissible with a swipe.
  Example: "Getting to know you, 2 of 5" / "Your people" / "Start".
- **Pulse card:** "How are you today?" with two rows of five small circles (mood, energy; labels in meta at
  each end: "low" / "great"), an optional one-word field, and "Save". No emoji faces.
- **"+" button** at the left of the input (textMuted). Bottom sheet with four rows, each itemText with a meta
  line: "Practice a conversation" / "Rehearse something you're dreading." "Think through a decision" /
  "Get clear before you choose." "Untangle a thought" / "When a thought keeps hurting." "Start a journey" /
  "A few days of focused work on one thing."
- **Mode header:** when a mode is active, a thin line under the header in meta style: "Practicing: talking
  with Abbu", "Journey: Break the avoidance loop, day 3", etc., with an "End" text button at the right.
- **In-character lines** (practice mode): coach messages get a 2dp accent bar on the left edge and a meta
  label above the first one ("as Abbu"). Out-of-character messages return to normal style.

### Onboarding
- Welcome screen: the ridgeline, openingLine "Let's get to know each other.", meta "A few short steps over
  your first week. You can skip anything.", primary button "Begin".
- Values: chips in a wrapping grid (surface color; selected = accent outline and accent text), "Pick your
  top 5", then a reorder list (drag handles) "Put them in order".
- Big Five: 5 statements per page in itemText, each with a 5-step row of circles labeled in meta
  "Disagree" … "Agree". A thin progress line at the top. Meta note at the start: "A reflection tool, not a
  diagnosis. Answer as you are, not as you'd like to be."
- Snapshot view: same layout as the letter view (title in openingLine, sections in letterBody). Reachable
  from the top of What I know as a row: "Your snapshot" / its title.

### Mirror
Weekly letters (every Sunday evening) and monthly letters (1st of each month), newest first, plus a yearly
letter each January.
```
 Mirror                                 ⋯

 October                                 monthly row: Newsreader 24sp
 The month you stopped running           itemText, textMuted
 from the hard chapter
 Monthly letter, 1 to 31 Oct             meta
 ─────────────────────────────────────  hairline above and below monthly rows
 •  Week of 26 Oct                       weekly row: Newsreader 18sp (• = unread dot)
    The week you admitted it was         meta size 15sp, textMuted
    fear, not laziness
 Week of 19 Oct
 …
```
- Rows, not cards. Monthly rows are larger and set off by hairlines; weekly rows are lighter and tighter,
  so the month reads as a chapter and the weeks as pages.
- Title line = first line of the letter text.
- Unread letters get a 6dp apricot dot before the title, and a matching dot on the Mirror nav icon until
  every new letter has been opened. No notifications.
- Tap opens the **letter view**: full screen, background color, 28dp side padding, a meta line
  ("Weekly letter, 20 to 26 Oct"), the title in openingLine style, then letterBody. Section titles inside
  monthly letters in label style, textMuted, 32dp above each. ⋯ menu: Delete letter.
- Long-press a row → Delete letter. Always confirm: "Delete this letter? This can't be undone."
  Buttons: Cancel / Delete (danger color).
- Error state: never show raw errors on Mirror. If a letter failed after retries, show one meta line at the
  top, "This week's letter is delayed.", with a "Try again" text button, and hide the empty-state text.
- Overflow ⋯ on the list: "Write this week's letter now". Disabled (with meta text "Needs at least one real
  conversation this week first.") if there's no meaningful conversation since the last weekly letter.
- Empty state (upper third, Newsreader 20sp, textMuted): "Your first letter arrives on Sunday evening,
  once we've talked. It'll show you what I'm noticing about you."

### Growth tree
The most beautiful screen in the app. It should feel like a hand-drawn ink tree in the mountain night:
quiet, alive, and personal. Drawn with Compose Canvas, no images.

**Anatomy (it's a map of his whole life)**
- **Roots:** his top values (from the values sort), drawn as thin roots below the ground line, each labeled in
  meta text. Roots exist from day one: the tree starts from what he values.
- **Ground:** the signature ridgeline stroke as the horizon behind the tree's base.
- **Trunk:** grows taller with time. One faint ring line across the trunk per life chapter (every 3 months),
  so years of growth are visible in the wood itself.
- **Branches:** one per life area, but a branch appears only when its first leaf is accepted. Inner growth
  areas (eq, mindset, character, habits) grow on the left, life areas (studies and career, health,
  relationships, money, meaning, rest and joy) on the right. Branch thickness grows with its leaf count.
  Sub-branches (e.g. "FAR", "Abbu", "Phone") fork from their area branch.
- **Leaves:** one per accepted milestone. Small rounded leaf shapes in accent (apricot). Leaf shape varies
  slightly by type: habit built (full leaf), habit unlearned (leaf with a small notch, a thing let go),
  accomplishment (leaf with a tiny bud), inner growth (leaf with a vein line), relationship (two leaves
  joined). The newest leaf is slightly brighter for a week.

**Look**
- Strokes in textMuted, 1.5dp for twigs up to ~5dp for the trunk, with soft tapered ends. Leaves in accent;
  everything else monochrome. Night: on the background color; Day: same layout with Day tokens.
- Deterministic layout: seeded by branch and leaf ids, so the tree looks the same every time and only changes
  when it grows. Gentle natural curves, never symmetrical, never cluttered: if a sub-branch has more than
  ~12 leaves, they cluster into a fuller twig with a small count in meta.

**Interaction**
- Full screen from Path. Pinch to zoom, drag to pan, double-tap to reset.
- Tap a leaf → bottom sheet: title (Newsreader 22sp), date in meta, description (itemText), the evidence
  lines (meta), and his quote if there is one. Long-press → Remove.
- Tap a branch label → list of that area's leaves in time order.
- A "Timeline" toggle at the top shows the same leaves as a simple dated list (also the accessible version).
- **Growing animation** (only when he accepts a leaf): the branch extends (600ms), then the leaf unfolds from
  a point (500ms), with a single light haptic. Respect "remove animations".
- **Empty state:** just the roots (his values), ground and a small seedling trunk, with the line in Newsreader
  20sp: "Your tree starts with your roots. It grows only with what you really do."

**Path preview:** a 180dp-tall card at the top of Path showing the whole tree small, the leaf count in meta
("12 leaves, 5 branches"), tapping opens full screen.

### Path (journeys + promises)
Below the growth tree preview: the active journey, if any: journey name in Newsreader 22sp, meta "Day 3 of 7", a row of
small dots for the days (kept days in accent, today outlined), today's step title in itemText, and a text
button "Start today's step". If the journey was adjusted, the reason appears in meta under the step
("Twenty minutes felt like too much this week, so tomorrow is ten."). Paused journeys show "Paused" and "Resume". If no journey: a quiet row "Start a journey" that opens the journey list
(name in itemText, one-line description in meta, length in meta). Below it, the promises section:

### Promises
```
 Promises

 You've kept 6 of your last 7.            itemText (only when ≥3 resolved)
 That's 4 in a row.                       meta

 Read one page of FAR, phone in           itemText
 the other room
 Tonight at 9:00 pm   🔔                  meta (accent when due today); bell icon only if reminder set
 Testing whether a smaller start          meta, textMuted (the "why")
 beats the heavy feeling
 ─────────────────────────────────────
 Past promises  ⌄                         label, collapsed by default
```
- Tap a promise → bottom sheet: the promise, its why, then "I kept it" (accent), "Change it", "Let it go",
  and "Remove reminder" if one is set. Swipe right = kept, swipe left = let go, with undo snackbar.
- Past promises show their status and, when present, the lesson in meta text ("What worked: phone out of
  the room"). Kept ones get a small apricot check.
- Empty state: "When we decide on something for you to do, it'll show up here so we can keep track together."

### What I know (calm, even after years)
The screen must feel calm and scannable no matter how much Purpose knows.
- Top: title, intro meta line, search field.
- **Your snapshot** row: title of the snapshot, opens the letter-style view. Then **Chapters** row if any
  ("4 chapters of your story"), opening the list.
- **Sections collapsed by default**, each as one row: heading (Newsreader 22sp) and a meta count
  ("About you, 18" / "How you work, 6 patterns"), with a chevron. Tapping expands it in place. Order:
  About you, Your future self, Your strengths, How you work, What works for you, What doesn't, Still on your
  mind, People, Your life areas, Recent moments, Archived.
- Inside a section: items show at most 2 lines (ellipsized); tap an item to expand it fully, tap again to
  edit. Meta line under each item (its label, confidence, "seen 4 times").
- Show the top 5 items per section, then "Show all (N)".
- **Your future self** is shown as one short paragraph, not split into items.
- Search shows matching items across all sections, including archived ones, with the section name in meta.
- 32dp between collapsed section rows; hairlines between items inside a section.

### Small feedback
- Rename a conversation, save a setting, save a prompt: a short snackbar ("Renamed",
  "Saved"), surface color, 2 seconds.
- Swipe actions (promises, What I know): snackbar with "Undo" for 5 seconds.
- "Promise saved" inline line under a reply includes a small "Undo" text button (deletes the promise and
  cancels its reminder), in case it saved something he didn't mean.


### Settings
Main level, in this order:
1. **Your key**: masked field; once saved, show "Connected" with an accent check and a "Change" button.
2. **How direct should I be?**: segmented Gentle / Balanced / Firm.
3. **Theme**: Night / Day / Follow system.
4b. **Daily pulse**: on/off (off by default). **Read replies aloud**: on/off (off by default).
4c. **Hide in recent apps**: meta line under it: "Also blocks screenshots. Turn off temporarily if you
    need to take one."
5. **Backup**: Export / Restore with passphrase (keep the existing explanation text).
6. A meta line: "This month: about $0.42" (from usage stats).
7. **Advanced** (collapsed): provider, base URL, models, temperature, thinking switch, prices, detailed usage.

Prices are prefilled with defaults so he never has to type them.

## Polish pass (from QA review)
- **Reading comfort:** coachBody must render at 18sp with 28sp line height (about 1.55) and paragraph gaps of
  14dp. If replies look tight, the spec isn't being applied: check the Text styles. Letter spacing stays at
  the font's default.
- **Input bar:** "+", text field, mic and send are vertically centered on one line; when the field grows to
  several lines, icons stay aligned to the bottom line. When messages scroll behind the input area, show a
  hairline on its top edge (no shadow, no blur).
- **Haptics** (light, system "click" level, respects the system setting): sending a message, toggling Just
  listen, opening the "+" sheet, marking a promise kept, and saving the daily pulse. Nothing else.
- **Thinking:** the typing dots pulse slowly (1.2s cycle, opacity 30% to 100%, staggered). No glow, no logo
  animation.
- **New messages:** fade in plus a 6dp upward slide, 180ms.
- **Smooth streaming:** text appears as a steady, even flow, never in jumps. See UPDATE-11 for the method.
- **Sending:** the keyboard closes as soon as he sends (he can tap the field to reopen it).
- **Tab switch:** the active icon and label cross-fade to accent over 150ms. No pill, no scaling, no glow.
- **What I know spacing:** 48dp above each section heading, 16dp between items, hairline between items.
  Life-area status as a small quiet tag: label style, 4dp by 10dp padding, radius 8dp, surface background;
  "growing" in accent text, "steady" and "stuck" in textMuted (stuck is not an error, so never red).
- **Empty states:** show the ridgeline stroke (small, textMuted at 30%) above the empty-state text on Mirror,
  Path and What I know, so they feel intentional.

## Copy rules
Plain, warm, sentence case. Buttons say exactly what happens ("Delete letter", "Save", "I kept it").
Empty states invite, they don't apologize. Errors say what happened and what to do:
"Couldn't reach DeepSeek. Check your internet and try again."
