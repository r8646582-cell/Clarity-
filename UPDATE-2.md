# UPDATE 2 — deeper coach, weekly + monthly letters, privacy and voice

Do the tasks in order, commit after each, and tell Umair in plain language what changed.

**First:** if UPDATE-1.md hasn't been completed yet, do it fully before this file. Where they differ,
this file wins (for example, `report.md` is replaced by two new letter prompts).

Files in this update (prompts are Umair's, don't edit them):
- `app/src/main/assets/prompts/persona.md`: rewritten with a world-wisdom library and the craft of a wise reply.
- `app/src/main/assets/prompts/reflection.md`: adds `significance`, `tone`, `his_words`, `ideas_used`.
- `app/src/main/assets/prompts/letter_weekly.md` and `letter_monthly.md`: replace `report.md` (delete it).
- `CLAUDE.md` and `DESIGN.md`: updated. Merge with your existing versions; don't lose changes you made.

## 1. Reflection fields and memory
- Parse and store the new reflection fields: `significance`, `tone` on Session; `his_words` → `Quote` table;
  `ideas_used` → `IdeaUsed` table. Track `userMessageCount` per session.
- Add to the chat context block (see CLAUDE.md Core flow 1): up to 10 recent quotes with dates, and the
  last 20 idea tags under "Ideas used recently". Keep the context block stable within a session (cache).
Done when: after two real conversations, the second session's context block shows quotes and idea tags.

## 2. Letters: weekly, monthly, yearly
Implement CLAUDE.md "Core flow 3: Mirror letters" exactly:
- Weekly every Sunday 20:00 local, monthly on the 1st, yearly on Jan 1. WorkManager with catch-up on launch.
- Full transcripts sorted by significance within a ~120k-token budget; overflow sessions sent as summaries.
- Rename/migrate the old `Report` table to `Letter` (kind, title, content, readAt). Delete the old test letter.
- Title = first line of the output; store the rest as content.
Done when: a test run with fake sessions produces a weekly letter, and a monthly letter that includes the
weekly letters as input.

## 3. Mirror screen
Build Mirror per DESIGN.md: monthly and weekly rows styled differently, unread dots on rows and on the
Mirror nav icon (cleared when opened), letter view, delete with confirm, "Write this week's letter now".

## 4. Talk about a letter
At the bottom of the letter view, a quiet text button: "Talk about this letter". It starts a new session
whose context block includes "He just read this letter you wrote him:" plus the letter text, and shows the
opening line "What stayed with you from this one?"

## 5. Privacy lock
- App lock with BiometricPrompt (fingerprint/face, falling back to device PIN). Ask on launch and after
  5 minutes in the background. Setting "Lock Purpose" (on by default).
- Setting "Hide in recent apps" (on by default): use FLAG_SECURE so the app's content doesn't show in the
  recents screen or screenshots.
Done when: reopening the app after 5 minutes asks for fingerprint/PIN, and recents shows a blank card.

## 6. Voice typing
- A mic icon (textMuted) inside the input field, left of the send button. Tap → Android SpeechRecognizer;
  the transcribed text goes into the input box so he can edit it before sending. Never auto-send.
- Settings, under Advanced: voice language (English, Urdu, or phone default).
- Add a meta line under the setting: "Voice typing uses your phone's speech service."
Done when: speaking a sentence fills the input box correctly in English and Urdu.
