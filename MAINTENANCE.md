# Keeping Purpose healthy (for Umair)

You can run and improve Purpose on your own from now on. Here's how.

## Every week (2 minutes)
Settings > Advanced > Developer > **Health check**. Everything should be green.
- Amber or red on letters, reflection or reminders: almost always the phone killing the app. Open
  Settings > Keep Purpose reliable and fix what it shows (battery: no restrictions; autostart: on;
  lock Purpose in recents).
- Possible duplicates: tap "Clean up now".
- Cost near budget: fine, chat just switches to the cheaper model until next month.

## When a reply feels off (too shallow, too pushy, preachy, robotic)
1. Copy the exact reply and what you said before it.
2. Developer > **Prompt editor** > persona. Find the rule that should have prevented it and make it clearer,
   or add a short example of the right reply near the end of examples.md. Small changes work best: one
   sentence at a time.
3. Run the **Test bench** before and after. If more scenarios pass after, keep it. If worse, "Reset to
   built-in" or undo your edit.
4. Use "Export all prompts" now and then and save the file somewhere safe.
Tip: models copy examples more than they follow rules. A good example beats a new rule.

## If replies keep feeling thin
Settings > Advanced > "Always use the deep model". Costs a little more, noticeably deeper.

## When something breaks
- Developer > Error log or Last crash: copy it.
- Write one line: what you did → what you expected → what happened.
- Give both to Claude Code (or any coding assistant) along with this repo. CLAUDE.md, DESIGN.md and the
  UPDATE files explain the whole app, so any assistant can pick it up.

## Installing new versions without a computer
If prompts or code change in the GitHub repo: create a new tag/release named like v1.0.1 on the GitHub
website. GitHub Actions builds the APK automatically; download it from the Release page and install.
Your data stays because it's the same signed app. Back up first anyway (Settings > Backup > Export).

## Your data
- Weekly automatic backup is on: check "Last backup" in Settings now and then.
- Keep your backup passphrase somewhere safe. Without it, backups can't be opened.
- Anything you don't want remembered: use Off the record, or Forget this conversation afterwards.

## Part B (your first real week)
Use Purpose normally. On Sunday evening, the weekly letter should arrive on its own. After a week, open
Health check: if it's all green and the letter felt like it truly read your week, Purpose is working as designed.

## Once a year (to keep Purpose for life)
1. Export a backup AND "Export my life", and save both somewhere outside your phone (Google Drive, a USB drive).
2. If you can, test a restore on another phone or after a reset. A backup you've never restored is a hope, not a backup.
3. Check your signing keystore file and its 4 passwords are still saved safely. Without them, you can't update the app.
4. Check your DeepSeek balance and price, and keep a backup provider set in Settings.
5. Read your yearly letter and your chapters. That's the point of all of this.

## If you change phones
Install Purpose from your GitHub Releases on the new phone, then Settings > Backup > Restore with your passphrase.
Re-enter your API key (it's never in backups). Redo "Keep Purpose reliable" on the new phone.

## If DeepSeek ever shuts down or gets too expensive
Settings > Advanced > AI provider: switch to any other provider that offers an OpenAI-compatible API (or Anthropic).
Your memory, letters and history all stay; only the "brain" changes. Run the test bench after switching and
adjust prompts if replies feel different.
