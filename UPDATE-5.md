# UPDATE 5 — natural voice, smart routing, and bug fixing

Umair is using the app. Reported bugs below come first. Then the voice upgrade. Then a full self-audit,
because he can't easily find bugs himself. Commit after each task and explain changes in plain language.

**First:** complete UPDATE-1 to UPDATE-4 if not done. Overwrite prompt files; merge CLAUDE.md and DESIGN.md.

## 1. Bug: reply hidden behind the keyboard (critical)
When he sends a message, the reply appears behind the open keyboard and he has to scroll up every time.
- Use edge-to-edge with `WindowCompat.setDecorFitsSystemWindows(window, false)` and `Modifier.imePadding()`
  on the input bar; set `android:windowSoftInputMode="adjustResize"` in the manifest.
- On send: scroll to the newest item. During streaming: keep the list pinned to the bottom as text grows
  (`animateScrollToItem` on content-size changes), unless the user scrolled up manually; then show the
  "↓" jump button (DESIGN.md).
- Also scroll to bottom when the keyboard opens.
Done when: with the keyboard open, sending a message always shows his message and the full streaming
reply without manual scrolling, on a small screen too.

## 2. Bug: values sorting gets stuck (onboarding)
The reorder step in the values card sort freezes or stops responding.
- Find the cause (common: drag state not reset, list keys not stable, recomposition during drag, gesture
  conflict with the scroll container). Use stable keys (value names) and a well-tested reorder approach.
- Add a fallback that always works: up/down arrow buttons on each row, so ordering never depends on drag alone.
- Make sure he can always go Back and Next, and that progress is saved if he leaves mid-way.
Done when: he can reorder all 5 values by drag and by arrows, leave, come back, and finish.

## 3. Screenshots
"Hide in recent apps" (FLAG_SECURE) also blocks screenshots. Keep it, but make the Settings text say so
(DESIGN.md), and apply the change instantly when toggled (no restart needed).

## 4. Voice examples
Load `prompts/examples.md` right after the persona in every chat request (CLAUDE.md Core flow 1, item 1b).

## 5. Model routing
Implement CLAUDE.md "Model routing (chat)". Unit-test the router with sample messages
(heavy English, heavy Roman Urdu, short casual, mode active, first message).

## 6. Full self-audit (he can't test everything, so you must)
Go screen by screen and fix what you find. Write unit and Compose UI tests where practical. Check at least:
- **Keyboard:** every screen with a text field (chat, settings, edit sheets, pulse word, backup passphrase)
  stays usable with the keyboard open; nothing important is hidden behind it.
- **Small screens and big fonts:** set system font size to largest and display size to largest. No clipped
  text, overlapping elements, or unreachable buttons.
- **Back button:** works sensibly everywhere (closes sheets first, never exits mid-onboarding without saving).
- **App killed and reopened:** mid-conversation, mid-onboarding, mid-questionnaire, mid-journey. Nothing lost.
- **Rotation / dark-light theme switch** while on each screen: no crash, no lost input.
- **No internet / API errors / wrong API key:** clear message, retry works, nothing saved half-broken.
- **Long content:** very long messages, long letters, 50+ promises, many notes. Scrolling stays smooth.
- **Empty states:** every screen with no data looks intentional.
- **Background jobs:** reflection, weekly/monthly letters, reminders after reboot. Add a hidden
  "Developer" section in Settings > Advanced (tap the version number 7 times) with buttons to run
  reflection now, write a test weekly letter, and fire a test reminder, so these can be tested on demand.
- **Hidden lines:** `[[promise:…]]` and `[[mode:…]]` never flash on screen, even briefly.
- **Crashes:** add a simple local crash log (Settings > Advanced > Developer > "Last crash") that stores
  only the stack trace, never message content, so Umair can copy it and share it.
Report what you found and fixed as a plain list for Umair.
