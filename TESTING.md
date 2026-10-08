# How to test Purpose (for Umair)

You don't need to be a tester. Do these once after each update, takes about 15 minutes.
If something's wrong, tell Claude Code exactly what you did, what you expected, and what happened.
Example: "On Talk, I sent a message with the keyboard open. Expected to see the reply. It went behind the keyboard."

Tip: Settings > "Hide in recent apps" blocks screenshots. Turn it off while testing, then back on.

## Talk
1. Send a short message with the keyboard open. Can you see the whole reply without scrolling?
2. Send a long, heavy message (how you really feel). Does it show "thinking…" and then a deeper reply?
3. Scroll up during a reply. Does a ↓ button appear and work?
4. Agree to a small action. Do you see "Promise saved" under the reply? Does it appear in Path?
5. Ask for a reminder 2 minutes from now. Does the notification come?
6. Turn on "Just listen". Does it stop giving advice?
7. Try the "+" menu: start each tool once (practice, decision, untangle, journey).

## Other screens
8. Path: start a journey. Is day 1 on the Talk screen?
9. What I know: edit one item, delete one item. Do the changes stay after closing the app?
10. Mirror: open a letter, delete it. (Settings > Advanced > Developer > Write test weekly letter.)
10b. Settings > Advanced > Developer > Health check. All green? Anything amber or red says what to do.

## Stress tests
11. Close the app completely mid-conversation (swipe it from recents), reopen. Anything lost?
12. Turn off internet, send a message. Clear error and a working retry?
13. Set phone font size to the largest. Does every screen still look okay?
14. Restart the phone. Do reminders still come?
15. Send a heavy message, switch to another app for 30 seconds, come back. Is the full reply there?
16. Send a message and lock the phone. Unlock after a minute. Is the reply complete?
17. Send a heavy message, then force-stop Purpose mid-reply (phone Settings > Apps > Purpose > Force stop).
    Reopen it. Do you see the partial reply with "Reply interrupted." and does Try again write it in full?
18. If the app crashes: Settings > Advanced > Developer > Last crash. Copy it and paste it to Claude Code.
19. Send 10 heavy messages in a row. Every one should finish; none should say "Couldn't finish that reply".
20. Turn on 3-button navigation (phone Settings > Display > Navigation). Is the bottom bar dark, not grey?
21. Smooth replies (judge this on the release build from GitHub Releases, never a debug build):
    - Send a message with the keyboard open. The keyboard should close straight away; tap the box to reopen it.
    - Send a long, heavy message. The reply should flow in word by word, evenly, with no jumps or pauses
      from the first word to the last, and your message should stay near the top while it does.
    - To measure: phone Settings > Developer options > "Profile HWUI rendering" > "On screen as bars".
      While a long reply streams, the bars should stay mostly under the green line, with no regular red spikes.
      Turn it off afterwards.
    - Phone Settings > Accessibility > "Remove animations" on: replies should then appear as they arrive,
      without the smooth reveal.

## Update 12 checks
22. Getting to know you: start "Your story" and talk it through. When the coach wraps it up, does the header line
    go away and a card say "Your story: done / Next: Your people" with "Continue now" and "Later"? Does the count
    on Talk's card go up?
23. In a story conversation, also talk about the people in your life in some depth. After it ends (or after the
    next launch), does "Your people" count as done too?
24. Scroll up in a long conversation. Is the ↓ button in the middle, clear of your message bubbles?
25. What I know: does everything say "you", never "he" or "his"? ("Cleaning up what I know…" shows while it runs.)
26. Agree to a reminder for a time that has just passed (say "remind me at" a minute ago). Does it come straight away?
27. Path: change a promise's due time. Does its reminder move with it?
28. Open an old conversation from the drawer, then press back. Are you back in the current one (not out of the app)?
29. Undo on "Promise saved" or rename a chat: does the message sit above the box you type in, not on top of it?

## Updates 13–18 checks
30. Values: in "Your values", does the coach name all five of your values back, one at a time? (Test bench has a
    scenario for this.)
31. Ask the coach to start a journey that doesn't exist ("start the Quieting Anxiety journey"). Does the journey
    list open instead, with only real journeys?
32. Test bench: is there a "questions: N" line under each reply, red when it's more than one?
33. Settings > Backup > "Export my life": does it warn that the export is unencrypted, then save a Markdown file
    and a JSON file you can open on a computer?
34. Restore test: back up, install Purpose on a second phone or an emulator, restore. Is everything there
    (conversations, letters, What I know, promises, the tree)? Then tap "Done today" next to the restore test in Settings (Once a year).
35. What I know: sections are folded with a count; tap to open. Does the search find things in every section and
    in Archived? Does "Chapters" open the life chapters?
36. Developer > "Generate 5 years of fake data": writes 5 years of fake data into a separate test database (never yours) and times
    the screens. Every line should say well under a second. It deletes the test data afterwards.
37. Airplane mode: open old conversations, letters and What I know. Write a message. Does it say "Will send
    when you're online", and send by itself when the internet is back?
38. Settings > Advanced > Backup provider: add one, then type a wrong main API key. After 3 failed replies,
    does Talk say "Using your backup AI for now." and keep answering? Put the right key back: does it switch back?
39. Settings > Usage: is this month's cost split by feature, with the cache hit rate? It should be 80% or more
    after a few longer conversations.
40. Developer > Compare models: enter another model's name and prices, run it, judge the replies. Is the cost
    per run shown next to your own models' run (run "Run all" once first)?
41. Path: does the tree preview show at the top? Tap it: full-screen tree, with your values as roots. Long-press
    a leaf: "Remove". Developer > "Preview tree with sample leaves" shows sample trees with 10, 50 and 300 leaves.
42. Developer > "Look for growth-tree proposals now": if there's strong evidence, Talk shows "Something I've noticed" with
    "Add to your tree" and "Not yet". Adding it grows the leaf on the tree.
43. Finish a journey day where it didn't go well. Does Path show the reason for the adjustment under the next
    step? If the coach paused the journey, does Path say "Paused" with "Resume"?

## Update 19 checks
44. Open Purpose after midnight. Does the home greeting say "Still up, Umair?" and never ask how something went
    that hasn't happened yet? "Coming up" shows promises due in the next 48 hours with the right time.
45. Ask "what's coming up for me?" and "what time did I say that?". Are days and hours right?
46. Correct a fact ("the blocker is called Dechainer"). After the conversation is reflected, does What I know show
    the corrected entry with a "confirmed" tag, and does the coach keep using it?
