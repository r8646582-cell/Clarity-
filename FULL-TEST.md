# Purpose — Full Test Plan

Do this once before Update 10. Part A takes about an hour. Part B runs over a week in normal use.
Before starting: Settings → turn OFF "Hide in recent apps" so you can take screenshots. Turn it back on after.

For every test, mark it: ✅ works, ❌ broken, ⚠️ works but feels off.
For anything ❌ or ⚠️, write one line: what you did → what you expected → what happened.
Example: "Sent message with keyboard open → see reply → reply went behind keyboard."

---

## Part A — One sitting

### 1. First impression and look
- [ ] 1.1 Open the app cold. Does it open fast, with a dark splash and the mountain ridgeline?
- [ ] 1.2 Is the phone's bottom navigation bar dark (matching the app), not light grey?
- [ ] 1.3 Go through all 4 tabs. Same fonts, colors and spacing everywhere? Anything that looks "default Android"?
- [ ] 1.4 Settings → Theme → Day. Every screen readable? Then back to Night.
- [ ] 1.5 Phone Settings → font size and display size to largest. Check every tab and the chat. Anything cut off or overlapping? Set back after.

### 2. Talk basics
- [ ] 2.1 New conversation: is there a personal opening line over the ridgeline?
- [ ] 2.2 Send a short message with the keyboard open. Do you see your message and the full reply without scrolling?
- [ ] 2.3 Send a long, heavy message (how you really feel). Do the dots show "thinking…"? Is the reply deep (2-4 paragraphs)?
- [ ] 2.4 Long-press that reply → Details. Does it say Deep / Pro / thinking on?
- [ ] 2.5 Send "hey" → long-press → Details. Does it say Fast / Flash?
- [ ] 2.6 While a long reply streams, scroll up. Does a ↓ button appear, and does it take you back down?
- [ ] 2.7 Do you feel a light vibration when sending?
- [ ] 2.8 Any reply showing bullets, bold, emoji, or a visible "[[promise" line? (Should never happen.)
- [ ] 2.9 Write in Roman Urdu. Is the reply clean Roman Urdu, no broken words?
- [ ] 2.10 Turn on "Just listen" and share something hard. No advice at all?
- [ ] 2.11 Mic button: speak a sentence in English, then in Urdu. Does the text appear in the box without sending?
- [ ] 2.12 Settings → Read replies aloud ON. Does a speaker button work on replies? Turn off after.

### 3. Leaving mid-reply
- [ ] 3.1 Send a heavy message, switch to another app for 30 seconds, come back. Full reply there?
- [ ] 3.2 Send a message, lock the phone, unlock after a minute. Reply complete?
- [ ] 3.3 Send a message, then swipe Purpose away from recents. Reopen: partial reply with "Reply interrupted" and a working Try again?
- [ ] 3.4 Turn on airplane mode, send a message. Clear error, and does Retry work after turning internet back on?

### 4. Actions and promises
- [ ] 4.1 Talk about something you're avoiding until Purpose suggests a small action. Did it ask for a real yes before saving?
- [ ] 4.2 Say yes. Does "Promise saved" appear under the reply? Is it in Path with its "why"?
- [ ] 4.3 Ask for a reminder 2 minutes from now. Does the notification come on time? Does tapping it open Talk?
- [ ] 4.4 Path → tap the promise → "I kept it". Vibration? Moves to past promises with a check?
- [ ] 4.5 Make another promise, swipe it left (let go). Does Undo work?
- [ ] 4.6 Make a promise with a reminder, restart the phone. Does the reminder still come?

### 5. Tools and modes ("+" button)
- [ ] 5.1 Practice a conversation (e.g. telling your father you need a break). Does it ask a few questions, then play him realistically? Are his lines marked "as Abbu" with a colored bar?
- [ ] 5.2 Say "stop". Does it give real feedback and offer a replay?
- [ ] 5.3 Think through a decision. Ask it to "just decide for me". Does it refuse kindly and keep helping you think?
- [ ] 5.4 Untangle a thought ("I'm not enough"). One step at a time? No plans or scripts? Demand a straight answer: does it hold the line?
- [ ] 5.5 Is the mode shown under the header, and does "End" leave the mode?
- [ ] 5.6 In a normal chat, describe a conversation you're dreading. Does Purpose offer practice? Say yes: does it switch?

### 6. Conversations list
- [ ] 6.1 Swipe from the left (or ☰). See your conversations with titles, grouped by date?
- [ ] 6.2 Search a word you used earlier. Found?
- [ ] 6.3 Open an old conversation and continue it. Does it remember what was said in that chat?
- [ ] 6.4 Rename a conversation. Does it stay renamed?
- [ ] 6.5 ⋯ → Off the record. Is there a line saying nothing will be remembered? After ending it, it's NOT in the list?

### 7. Journeys (Path)
- [ ] 7.1 Start "Break the avoidance loop". Does day 1 show as a card on Talk?
- [ ] 7.2 Do the day 1 step. Does it end with a small action?
- [ ] 7.3 Path: day dots, "Day 1 of 7", today's step title showing correctly?
- [ ] 7.4 Check every journey description for wording mistakes (e.g. "he's" instead of "you've").

### 8. What I know
- [ ] 8.1 Does it show real things about you (not the QA test person)?
- [ ] 8.2 Patterns shown as chains ("trigger → behavior → payoff")? With "a guess / likely" and "seen X times"?
- [ ] 8.3 Edit one item, delete another. Close and reopen the app. Changes kept?
- [ ] 8.4 Is there enough space between sections? Life-area tags small and quiet ("stuck" not red)?
- [ ] 8.5 Snapshot: if onboarding is done, does "Your snapshot" open and read like a real portrait of you?

### 9. Onboarding (if not finished)
- [ ] 9.1 Values: pick 5, then reorder. Does dragging work? Do the up/down arrows work?
- [ ] 9.2 Leave in the middle, reopen. Does it continue where you stopped?
- [ ] 9.3 Big Five: 5 per page, progress line, can go back?
- [ ] 9.4 Onboarding conversation cards appear on Talk, one per day?

### 10. Mirror (letters)
- [ ] 10.1 Settings → Advanced → tap the version 7 times → Developer → write a test weekly letter. Does it arrive without errors?
- [ ] 10.2 Unread dot on the letter and on the Mirror tab? Gone after opening?
- [ ] 10.3 Does the letter quote your real words and end with an experiment for next week?
- [ ] 10.4 "Talk about this letter" at the bottom. Does it open a chat asking what stayed with you?
- [ ] 10.5 Long-press the letter → Delete. Confirmation, then gone?
- [ ] 10.6 Airplane mode → write a test letter. Only a quiet "delayed" line with Try again (no raw error)?

### 11. Settings and privacy
- [ ] 11.1 API key shows "Connected" (not the key itself)?
- [ ] 11.2 Tough love: try Gentle vs Firm with the same "I'll start Monday" message. Noticeable difference?
- [ ] 11.3 Lock Purpose ON. Leave the app 5+ minutes, come back. Asks for fingerprint?
- [ ] 11.4 Hide in recent apps ON. Open recents: is Purpose's card blank?
- [ ] 11.5 Help numbers: correct and verified by you? (Check them yourself once.)
- [ ] 11.6 Daily pulse ON. Does the card appear on Talk? Save it, vibration?
- [ ] 11.7 Backup → Export with a passphrase. File created? (Don't test Restore unless you have a fresh backup.)
- [ ] 11.8 Automatic weekly backup: choose a folder. Does "Last backup" update?
- [ ] 11.9 Keep Purpose reliable: battery shows "unrestricted"? Autostart button opens the right page?
- [ ] 11.10 Monthly budget shows this month's cost?

### 12. Coach quality
- [ ] 12.1 Developer → Test bench → run all. Mark each pass/fail. Copy the report.
- [ ] 12.2 Your honest gut check after a real conversation: did it feel wise and human, or like a bot? One sentence.

### 13. Clean up
- [ ] 13.1 Forget the QA test conversations (or Erase everything if memory still has the fake job/partner/dad).
- [ ] 13.2 Turn "Hide in recent apps" back ON.

---

## Part B — Over one week of normal use
- [ ] B1 Talk to Purpose for real at least 4 times this week.
- [ ] B2 After a few days, does What I know show new true things you said, without duplicates?
- [ ] B3 Does Purpose bring up a due promise naturally at the start of a chat?
- [ ] B4 Does it reference something from an earlier conversation correctly?
- [ ] B5 Journey: does day 2 appear the next day (not the same day)?
- [ ] B6 Sunday evening: did the weekly letter arrive on its own? Does it feel like it really read your week?
- [ ] B7 Any crash? Developer → Last crash → copy it.
- [ ] B8 Settings → this month's cost. Reasonable?

---

## How to send me results
Paste your ❌ and ⚠️ lines plus the test bench report. Screenshots help for anything visual.
