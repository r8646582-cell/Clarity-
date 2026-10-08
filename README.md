# Purpose

A private AI life coach for Android: a wise companion that learns you, spots your patterns,
holds you to your word, and writes weekly and monthly letters on who you're becoming.

- Build spec for Claude Code: `CLAUDE.md`
- Visual spec: `DESIGN.md`
- The coach's soul: `app/src/main/assets/prompts/persona.md`
- Learning engine prompt: `app/src/main/assets/prompts/reflection.md`
- Letter prompts: `letter_weekly.md`, `letter_monthly.md`
- Snapshot, journeys and modes: `snapshot.md`, `journeys.md`, `mode_*.md`

Private repo. Never commit API keys.

## Installing on your phone (from GitHub Releases)
1. On your phone, open github.com/Umaireeee/PURPOSE → **Releases** (on the repo's main page, right side or
   below the files).
2. Open the newest release (e.g. **Purpose 1.0.0**) and tap the `.apk` file under **Assets** to download it.
3. Open the downloaded file. If Android asks, allow your browser to install apps, then tap **Install**
   (or **Update**: your conversations and memory stay, because it's the same signed app).
4. Back up first anyway: Settings → Backup → Export.

A new version is built automatically whenever a version tag is pushed. To make one yourself from the GitHub
website: **Releases** → **Draft a new release** → **Choose a tag** → type a new one like `v1.0.1` → **Create
new tag** → **Publish release**. GitHub Actions builds the APK and attaches it to a release a few minutes
later (watch it under **Actions**). Bump `versionCode` and `versionName` in `app/build.gradle.kts` first when
the code changed, so Android sees it as an update.

## Where the prompts live
All of Purpose's prompts are plain text files in `app/src/main/assets/prompts/` (persona, examples,
reflection, letters, snapshot, journeys, modes, gardening, test bench, custom journeys). The app reads them at
runtime and fills in the `{{PLACEHOLDERS}}`.

## What it does under the hood (short)
- **Memory** is graded by evidence: only your own words and actions raise confidence. Retrieval combines keyword search
  with an on-device MiniLM model (no data leaves the phone for it). Facts that stop being true are retired, not erased.
- **Ledgers** compare what you said you value with what you did (promises kept per week), track where you and the coach
  disagree, and keep contradictions in your own words. Code counts; the model only describes.
- **Phone screen time** is opt-in, off by default, minutes per category only, deletable in one tap.
- **Slow jobs** (reflection, letters, chapters, gardening, snapshot) can each use their own model; chat keeps Fast/Deep.
- **Backups** are encrypted with a key from your passphrase (PBKDF2-HMAC-SHA256, 600,000 iterations; older backups at
  210,000 still restore).
- **Scoreboard:** `python3 tools/eval/score.py` (see `tools/eval/README.md`) measures whether a change helped.

## Developer menu
Settings → Advanced → **Developer** (the last row). It has:
- **Health check**: green, amber or red for reflection, letters, gardening, reminders, battery, failed jobs,
  memory size and duplicates, context size, cost, backup and prompt edits. "Copy health report" copies it.
- **Test bench**: runs every scenario in `testbench.md` off the record; mark Pass/Fail and copy the report.
- **Compare models**: runs the test bench on another model (by name, with its prices) and shows the cost per
  run next to your current models', so you can switch when something cheaper is just as good.
- **Generate 5 years of fake data** (a speed test in a separate database, deleted afterwards) and **Preview
  tree with sample leaves** (10, 50, 300).
- Buttons to look for growth-tree proposals and write owed chapters now.
- **Prompt editor**: see below.
- **Error log** and **Last crash**: copy them when something breaks (they never contain your messages).
- Buttons to run reflection, write a test weekly or monthly letter, fire a test reminder, and run memory
  gardening now.

## Prompt editor (tune prompts without rebuilding)
Developer → **Prompt editor** lists every prompt. Open one, edit it, tap **Save**: your version is stored in
the app's encrypted database and used instead of the built-in file from the next conversation. The meta line
says "Edited" or "Built-in"; **Reset to built-in** undoes your edits. **Export all prompts** shares every
prompt, as used now, as one text file. After a change, run the Test bench before and after.
To make an edit permanent for everyone (and future builds), paste it into the file in
`app/src/main/assets/prompts/` on GitHub and make a new release.

See `MAINTENANCE.md` for the weekly routine and what to do when something feels off.

## Building
Open the project in Android Studio (JDK 17+, Android SDK 35) and run the `app` configuration.
On first launch, open Settings (⋯ on Talk → Settings) and paste your DeepSeek API key.

Unit tests: `./gradlew :app:testDebugUnitTest`. CI (`.github/workflows/ci.yml`) builds the APK and runs them on every push
to a work branch.

Fonts (Newsreader, Hanken Grotesk) are bundled under the SIL Open Font License; see `licenses/`.
The Big Five items are the public-domain IPIP 50-item domain scales (see `memory/Onboarding.kt` for the source).

Room writes its schema to `app/schemas/` on build. Commit those files: future migrations need them.

## Updating safely (dependencies, Android, the database)
Purpose is meant to last for years, so every change must keep your data:
- **Database changes:** bump the version in `PurposeDatabase`, write a migration in `data/db/Migrations.kt`
  (add columns and tables; never drop data), build once so the new schema JSON appears in `app/schemas/`,
  commit it, and run `MigrationTest`. It runs every migration on real SQLite, from every schema version Room has
  exported, and checks that every row survives and that the result matches the schema Room expects. `1 → 2` has
  no exported schema (v1 predates the export), so the test rebuilds v1 from v2's own definitions minus what that
  migration adds. `fallbackToDestructiveMigration` must never be used: a failed migration shows
  an error screen instead of wiping.
- **Dependencies and targetSdk:** update one group at a time (for example Compose, then Room, then OkHttp),
  read its release notes, run the unit tests, then install the release build over your current app (never
  uninstall: that deletes your data). Back up first.
- **Before any big update:** Settings > Backup > back up now, and keep the backup passphrase and the signing key
  safe. Without the signing key, an update can't install over the old app.
- **New AI provider or model:** change it in Settings; prompts stay editable in the Prompt editor. Use Compare
  models first.

## Release builds (how to install Purpose on your phone)
Always install a **release** build: it's minified with R8 and not debuggable, so it's faster and smoother
than a debug build.

`.github/workflows/release.yml` builds a signed release APK, runs the unit tests, and attaches the APK to a
GitHub Release. It runs when you push a version tag (like `v0.3.0`), or by hand: on GitHub, open the repo →
**Actions** → **Release** → **Run workflow**. When it finishes, open **Releases** on your phone, download the
`.apk`, and open it to install.

Each release bumps `versionCode` and `versionName` in `app/build.gradle.kts`. The version shows at the bottom
of Settings → Advanced, just above Developer.

### One-time: a signing key, so updates install over the old app
Without it, every build is signed with a different throwaway key, and Android refuses to install a new build
over the old one (uninstalling wipes your data; export a backup first).

1. On a computer with Java installed, make a key (pick a strong password and remember it):
   ```
   keytool -genkeypair -v -keystore purpose.jks -alias purpose -keyalg RSA -keysize 4096 -validity 36500
   base64 -w0 purpose.jks > purpose.jks.txt
   ```
   (On a Mac, use `base64 -i purpose.jks -o purpose.jks.txt`.)
2. On github.com, open the repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
   Add these four, one at a time:
   - `PURPOSE_KEYSTORE_BASE64`: the whole contents of `purpose.jks.txt`
   - `PURPOSE_KEYSTORE_PASSWORD`: the password you chose
   - `PURPOSE_KEY_ALIAS`: `purpose`
   - `PURPOSE_KEY_PASSWORD`: the same password (keytool uses one password for both unless you set two)
3. Keep `purpose.jks` and the password somewhere safe, outside the repo. If you lose them, future builds can't
   update the installed app.

The next release you build is signed with this key, and every one after it installs as an update.
A version tag (like `v1.0.0`) refuses to build without these secrets, so a debug-signed APK can never
be published by mistake. The Actions log shows who signed each APK ("Show who signed it").
