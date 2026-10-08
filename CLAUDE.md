# CLAUDE.md — Purpose (Android life coach)

## What this app is
**Purpose** (package `com.umair.purpose`, app name "Purpose") is a private, personal AI life coach for one user (Umair). Not a chatbot, not a productivity tool.
It is a "mirror with a heart": a wise companion that learns him over time, spots his patterns,
holds him to his word with caring tough love, and writes reports that show him who he's becoming.

The **persona prompt** is the soul of the app and lives in `app/src/main/assets/prompts/persona.md`.
Do NOT write or rewrite it yourself. Umair supplies it. Your job is the body around it.

## Product rules (never break these)
- No notifications, streaks, badges, gamification, or nagging. The only exception: a reminder he explicitly
  asked for on a specific promise. The user opens the app when he wants to talk.
- No accounts, no cloud sync, no analytics, no telemetry. The only network calls go to the AI API.
- All data stays on the device, encrypted.
- Four tabs only: **Talk**, **Mirror**, **Path** (journeys + promises), **What I know** (+ Settings, onboarding,
  snapshot view, and a static Get Help screen).
- Chat UI is calm and minimal. Plain text messages, no markdown-heavy rendering, no emoji decorations.
- **All UI follows `DESIGN.md`.** Read it before any UI work. No default Material look.
- Letters (Mirror) are written automatically: weekly every Sunday evening, monthly on the 1st, yearly on
  Jan 1. The user never has to press a button for them, and he can delete any letter.

## Stack
- Kotlin, Jetpack Compose, Material 3, single-activity, Navigation Compose. minSdk 26.
- MVVM with ViewModels + Kotlin Flows. Hilt for DI.
- Room + SQLCipher for the encrypted DB. DB passphrase generated on first run, stored via Android Keystore.
- OkHttp (or Ktor) for the AI API, with streaming (SSE) for chat responses.
- WorkManager for reflection and report generation.
- kotlinx.serialization for JSON.

## Secrets & security
- The API key is entered by the user in Settings and stored encrypted (Keystore-backed). Never in code,
  BuildConfig, resources, or the repo.
- Repo must be private. `.gitignore` must cover `local.properties`, keystores, `*.jks`, and any `.env`.
- No logging of message content or API keys, even in debug builds.

## AI provider
- All AI calls go through one interface, `AiClient` (`chat(stream)`, `complete(json)`), so the provider can change.
- Provider and model names are set in Settings / a config object, never hardcoded in call sites.
- Provider: **DeepSeek**. Its API is OpenAI-compatible (base URL `https://api.deepseek.com`), so implement
  `AiClient` as a generic OpenAI-compatible client. Swapping providers later = new base URL + model names.
- Model IDs have changed recently. Before writing code, check DeepSeek's official docs for the current model list
  and put the IDs in config. Use the cheaper Flash-tier model for chat, and the stronger Pro-tier model
  for reflection and reports, where quality matters more and volume is low.
- Chat runs in non-thinking mode for speed; reflection and reports may use thinking mode if the model supports it.
- Use JSON output mode for reflection (`response_format: {"type": "json_object"}`; the prompt must mention JSON).
- DeepSeek caches automatically by prompt prefix, and cache hits are far cheaper. So the start of every
  chat request must be byte-identical within a session: persona first, then the context block, and only then
  the volatile parts (due promise, flags, messages). Never put timestamps or changing text near the top.
- Temperature for chat: 0.7 (configurable). Reflection: 0.3. Chat max_tokens ≥ 1500.
- Streaming: parse SSE line by line with a buffered UTF-8 reader; never decode raw chunks. Save a reply only
  after the stream completes.
- Log token usage per request (counts only, never content) to a local stats table, and show monthly
  usage and an estimated cost in Settings.

## Data model (Room entities)
- `ProfileEntry(key, value, updatedAt)` — "Who you are": values, future-self vision, life facts.
- `Person(id, name, relation, notes, updatedAt)` — people in his life.
- `Note(id, type, text, confidence, status, timesSeen, firstSeen, lastSeen, editedByUser)`
  - type: `pattern | thread | what_helps | what_doesnt`
  - confidence: `guess | likely | confirmed` — a pattern starts as `guess` and only rises when it repeats.
  - status: `active | resolved | deleted_by_user`
- `Promise(id, text, why, createdAt, dueAt, remindAt, status, sourceSessionId, resolvedAt, whatHappened, lesson, area)`
  - status: `open | kept | broken | renegotiated | dropped`
- `AreaStatus(area, status, note, updatedAt)` — status: `growing | steady | stuck`
  - areas: `eq, habits, mindset, character, studies_career, health, relationships, money, meaning, rest_joy`
- `Snapshot(id, createdAt, bigFiveJson, valuesJson, title, portrait)` — latest one is used in context.
- `OnboardingStep(step, sessionId, completedAt)` — steps: story, people, values, future_self, how_you_work.
- `Journey(id, name, startedAt, currentDay, totalDays, status, lastStepDate)` — status: active | done | stopped.
- `Pulse(date, mood 1-5, energy 1-5, word)` — optional daily check-in, one per day.
- `BehaviorEvent(id, sessionId, createdAt, whenText, situation, feelingBefore, action, payoff, outcome)`
- `Strength(id, sessionId, text, createdAt)`
- `Session(id, title, reflectedUpToMessageId, offTheRecord (never persisted if true), startedAt, endedAt, summary, reflected, significance, tone, userMessageCount)`
- `Quote(id, sessionId, text, createdAt)` — his exact words from reflection `his_words`.
- `IdeaUsed(id, sessionId, tag, createdAt)` — from reflection `ideas_used`.
- `Message(id, sessionId, role, content, createdAt)`
- `Letter(id, kind, periodStart, periodEnd, createdAt, title, content, readAt)` — kind: `weekly | monthly | yearly`
- `Settings`: tough-love level (`gentle | balanced | firm`), provider, model, API key ref.

Anything the user edits or deletes in **What I know** is final. Reflection must never re-create a note
the user deleted (check `deleted_by_user`).

## Core flow 1: Chat (context assembly)
Each chat request sends, in this order:
1. `persona.md` (system prompt).
1b. `prompts/examples.md` (voice examples), right after the persona, as part of the system prompt.
2. A generated "context" block: profile, people, active notes (with confidence), area statuses,
   up to 10 recent `his_words` quotes (dated), "ideas used recently" (the last 20 `ideas_used` tags),
   strengths, the last 15 behavior events, all open promises (with ids, due, why), recent kept/broken
   promises with their lessons, and "What the record shows" (see below).
3. Summaries of the last 5 sessions (dated).
4. At most ONE due/overdue open promise, flagged so the coach can raise it naturally.
5. Mode instructions, only when a mode is active: the content of `prompts/mode_<mode>.md` (plus, for journeys,
   today's step line from `journeys.md`).
6. Runtime flags: `now` (local date-time with timezone), `listen_only`, `tough_love_level`, and when relevant
   `mode`, `onboarding_step`, `journey_name`, `journey_day`, `practice_with`.
7. Current-session messages (trim oldest beyond ~40 turns).

Items 1–3 (including 1b) must not change during a session (cache prefix). Build them once at session start.

Keep this lean — never send full message history from past sessions.

## Reliability (non-negotiable)
- **Timeouts:** connect 30s. Streaming chat: 120s max gap between chunks. Non-streaming calls (reflection,
  letters, snapshot, gardening): read timeout 300s, because the Pro model with thinking and long inputs
  can take minutes. (OkHttp's 10s default read timeout is a likely cause of the Mirror letter failure.)
- **Background jobs** (reflection, letters, snapshot, gardening, backup): run as WorkManager jobs with
  network constraint, 3 retries with exponential backoff. Never show a raw error for a background job.
  After all retries fail, Mirror shows one quiet meta line ("This week's letter is delayed.") with a
  "Try again" text button, instead of any placeholder text.
- **Preconditions:** never call the API for a letter if there's nothing to write about (no meaningful
  conversation in the period); just skip.
- **Error log** (Developer menu): timestamp, job/screen, model, HTTP status, error type, error body from
  the API (it never contains his messages), and estimated input tokens. Never message content.
- **Message details:** long-press a coach message → "Details" sheet: tier (Fast/Deep), model, thinking on/off,
  input/output tokens, time to first token, total time. This verifies routing works.

## Replies survive leaving the app
A reply must finish even if he switches apps, locks the phone, or the screen turns off mid-reply.
- Generation must NOT live in the UI. Run each chat request in an application-scoped component that the
  UI only observes: a foreground service started when a request begins and stopped when it ends.
  On Android 14+ use foreground service type `shortService` (fits replies under ~3 minutes, no special
  permission); on older versions, a normal foreground service. Its notification is quiet and low-priority:
  "Purpose is replying…". If the reply finishes while the app is in the background, update that
  notification to "Purpose replied" (tap opens the conversation), and clear it when he opens the app.
- Write the reply to the database as it streams (status `streaming`, partial text saved about once a second),
  then `complete` at the end. This is for persistence only. While the app is open, the UI reads the live
  text from an in-memory StateFlow exposed by the generation service (updated on every chunk), NOT from the
  database. The database is only read when the app is reopened or the chat is loaded.
- Hidden `[[…]]` lines are parsed only on completion, never shown in partial text.
- If the process is killed anyway: on next launch, any message still `streaming` becomes `interrupted`.
  Show the partial text with a meta line "Reply interrupted." and a "Try again" text button that regenerates
  the reply (replacing the partial one). Never leave a message stuck in `streaming`.
- Reflection, letters and other background jobs already use WorkManager; keep them there.

## Keep Purpose reliable on Xiaomi/Redmi
His phone (Redmi, HyperOS/MIUI) aggressively kills background apps, which can also break reminders, letters
and reflection. Add Settings → "Keep Purpose reliable" with:
- A button that requests ignoring battery optimizations (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
  fine for a sideloaded app).
- On Xiaomi devices, a button that opens the Autostart settings (try the known MIUI security-center intents;
  if none resolves, open the app's system settings page).
- Plain instructions: Battery saver → No restrictions; Autostart → on; in Recents, long-press Purpose →
  lock it.
- A status line for each: "Battery: unrestricted" / "restricted".
Show this once as a gentle card on Talk after onboarding, and always in Settings.

## Deep replies must not fail (QA: frequent "Couldn't finish that reply" with thinking on)
Check and fix all of these:
- **max_tokens:** thinking tokens may count toward the limit. With a low limit, the model spends it all on
  thinking and returns no content. Set Deep max_tokens high (check DeepSeek's docs for the limit and use
  it; at least 8000). Fast stays at 1500+.
- **Reasoning chunks are a heartbeat:** chunks with `reasoning_content` (and no `content`) must reset the
  120s gap timer and must not be treated as empty or as errors.
- **finish_reason:** handle `length` (show what arrived plus Try again) and any error object in the stream.
- **Automatic fallback:** if a Deep request fails or produces no content, retry once automatically on Fast
  (no thinking), silently. Only show "Couldn't finish that reply" if the fallback fails too.
- **No stuck states:** every request has an end state (complete, interrupted, failed). "Try again" must reset
  the state machine; the thinking indicator must disappear in every end state. Add tests for each path.
- Log every failure (status, finish_reason, error body, tier, tokens) to the error log.

## Waiting indicator
From the moment he sends until the first visible text arrives, always show the typing dots, whatever the tier.
For Deep, add the meta text "thinking…" under the dots. If nothing arrives within 20s, change it to
"still thinking…". The QA test saw replies with no indicator, which suggests Deep routing may not be
happening: verify with the message Details sheet.

## Help numbers in context
Removed (Oct 2026, at Umair's request): there are no help numbers, Get Help screen, or safety net.

## Off the record
⋯ menu → "Off the record" starts a session that is never saved or reflected: messages live in memory only,
no promises with reminders unless he asks, no letters use it. Header line (meta): "Off the record: nothing
here will be remembered." Runtime flag `off_the_record: true`. The test bench always runs off the record.

## Conversations (like Claude's chat list)
Purpose keeps one shared memory, but conversations are separate threads he can return to.
- **Chat list:** a left drawer on Talk (opened by a ☰ icon at the left of the header, or swiping from the
  left edge). Top: "New conversation" button. Below: a search field (searches titles and message text,
  locally), then conversations grouped by Today, Yesterday, Previous 7 days, Previous 30 days, then by
  month. Each row: title (itemText, one line), date and mode if any (meta). Off-the-record chats never appear.
- **Titles:** from reflection's `title` field. Until the first reflection, use the first few words of his
  first message.
- **Continue any conversation:** opening a past conversation shows it fully, with the input bar active,
  exactly like the current chat. Sending a message reopens it as the active session. The session's mode
  (if any) resumes too.
- **Reflection for continued chats:** store `reflectedUpToMessageId` on Session. When a reopened session ends,
  reflect again with the full transcript and the marker line "--- ALREADY REFLECTED ABOVE THIS LINE ---"
  inserted after the last reflected message, so nothing is learned twice. Update the session summary to
  cover the whole conversation.
- **Context for continued chats:** the current memory context block (as always), then that session's own
  messages. If an old session is very long, include its summary plus the last ~40 messages.
- **New conversation:** "New conversation" in the drawer or the ⋯ menu ends the current session (triggers
  reflection) and opens a fresh one with the opening line.
- **Row actions:** long-press a row → Rename, Forget this conversation (same rules as below).
- **Forget this conversation:** confirm, then delete the session, its messages, and everything learned only
  from it: notes whose only source is that session, its behavior events, quotes, idea tags, strengths and
  promises. Notes with other sources just lose this one. Requires provenance: add `sourceSessionIds` to Note
  and `sessionId` everywhere it's missing (migration: backfill unknowns as empty, which means "keep").
- **Erase everything:** Settings > Advanced (type ERASE to confirm): wipes all data except settings and key.
  Umair needs this now: his QA testing filled memory with invented test facts (a job, a partner, a sick
  father). After this update, he should erase or forget those test conversations.

## Memory gardening
After each monthly letter, run `prompts/gardening.md` (Pro model, JSON mode) and apply merges, rewrites and
retirements in one transaction. Retired notes are kept with status `retired` (not shown, not sent).
Never touch user-edited notes.

## Cost guard
Settings: "Monthly budget" (default $5). At 80%, show a meta line in Settings. At 100%, route all chat to Fast
until the month ends (letters and reflection still run). Never block chatting.

## Automatic backup
Settings > Backup: "Back up automatically every week" to a folder he picks (Storage Access Framework, so
Google Drive or a local folder both work), encrypted with his backup passphrase (stored with Keystore once he
enables this). Keep the last 4 backups. Show "Last backup: 3 days ago" in meta.

## Haptics (QA: none worked)
Use a single helper: try `view.performHapticFeedback(HapticFeedbackConstants.CONFIRM or CONTEXT_CLICK,
FLAG_IGNORE_GLOBAL_SETTING not set)`; if that does nothing on his device, fall back to `Vibrator` with
`VibrationEffect.createPredefined(EFFECT_CLICK)` (API 29+) or a 20ms one-shot. Add the VIBRATE permission.
Setting "Vibration" (on by default). Use it for exactly the events in DESIGN.md "Polish pass", plus swipe
actions on promises. Test on his Xiaomi: HyperOS often ignores performHapticFeedback.

## Release builds
Make sure he installs a release build (R8/minify on, debuggable off), not a debug build; debug builds are
slower and janky. Set up a GitHub Actions workflow that builds a signed release APK and attaches it to a
GitHub Release on every version tag, so he can download and install it from his phone. Signing key goes in
GitHub Secrets; give him plain step-by-step instructions to add them in the GitHub website. Bump versionCode
and versionName each release, and show the version in Settings.

## One-time memory cleanup (QA: What I know still says "his mother and father")
Run memory gardening once after this update, including the profile entries ("rewrite_profile"), so every
note, strength and profile line addresses him as "you". Show "Cleaning up what I know…" while it runs.

## Built for a lifetime (Umair will use Purpose for years)
Memory must stay useful, fast and affordable after 5 or 10 years. Design for that now.

**1. Never lose data (most important)**
- Room: `exportSchema = true`, a written migration for every schema change, and migration tests for every
  version pair. `fallbackToDestructiveMigration` must NEVER be used anywhere; search the code and remove it
  if present. A failed migration must stop and show an error, never wipe.
- Backups: compress (gzip) before encrypting; keep the last 4 automatic backups; include a schema version.
- Test a full restore on a second device or emulator (fresh install → restore → everything there).
- "Export my life" (Settings > Backup): an unencrypted, human-readable export (one Markdown file plus
  JSON) of everything: conversations, letters, snapshot, what Purpose knows, promises. So his history
  outlives this app, this phone and this AI provider. Warn that it's unencrypted.

**2. Memory in layers, so it never grows without limit**
- **Core profile:** at most ~30 active entries. Reflection updates entries instead of adding (see the
  updated reflection prompt). Memory gardening merges and trims to stay under the cap.
- **Living notes:** caps of 15 active patterns, 15 what-helps, 15 what-doesn't, 10 open threads, 20
  strengths. When a cap is exceeded, gardening merges or archives the weakest (lowest confidence, oldest).
- **Life chapters:** every 3 months (and on Jan 1), write a chapter with `prompts/chapter.md`
  (Pro model). Chapters replace the old session summaries in the context block. Store them; show them in
  Mirror as a "Chapters" section (letter style).
- **Archive:** every session, message, letter, event, quote and retired note stays in the database forever,
  searchable, but never sent in full.

**3. Context stays the same size forever**
- Stable block: persona, examples, core profile, snapshot, active notes (capped), the last 2 chapters, the
  last 5 session summaries, available journeys.
- **Relevant memories:** add SQLite full-text search (Room FTS4) over session summaries, quotes, events,
  letters and archived notes. For each new user message, search with its keywords and add the top 5
  matches (with dates) to the volatile part of the request, labeled "Possibly relevant from the past".
  This is how Purpose remembers something from three years ago without sending three years of history.
- Hard budget: the whole context block under ~12k tokens. If over, trim oldest summaries first, then
  lowest-confidence notes. Health check shows the size.

**4. Speed with years of data**
- Indexes on sessionId, createdAt, status and type columns.
- Paging 3 for the conversations drawer, Mirror, past promises, archive and long conversations (load the
  latest 50 messages, more on scroll up). No screen ever loads everything at once.
- All database work off the main thread; no screen should take more than a moment to open even with
  10 years of data. Add a Developer button "Generate 5 years of fake data" (in a separate test database,
  never his real one) to prove this, then delete it.
- Run SQLite `VACUUM` occasionally (e.g. monthly, when charging and idle).

**5. Survive changes over the years**
- **Backup AI provider:** Settings > Advanced > "Backup provider" (any OpenAI-compatible API, or Anthropic):
  base URL, key, model names. If the main provider fails for 3 requests in a row (outage, shutdown, key
  expired, out of credit), switch to the backup automatically and show a quiet line in Talk: "Using your
  backup AI for now." Switch back when the main one works again. Works with no backup set (then just errors).
- **Offline:** with no internet he can still open everything, read all conversations, letters and memory,
  and write messages. Messages written offline are queued, shown as "Will send when you're online", and
  sent in order when the connection returns. Nothing is ever lost because of no internet.
- **Health check yearly items:** last successful restore test (he marks it), backup age, keystore saved
  (he confirms once a year), provider key expiry if known.
- AI provider and model names stay configurable (already); prompts stay editable (Prompt editor).
- Keep dependencies and targetSdk reasonably current; note in README how to update safely.

## Cost efficiency (keep it cheap for decades)
- **See where the money goes:** Settings > usage shows this month's cost split by feature (chat Fast, chat
  Deep, reflection, letters, snapshot/chapters/gardening, test bench) and the cache hit rate. Keep 24
  months of monthly totals.
- **Cache hits:** the stable prefix (persona, examples, context block) must be byte-identical across all
  messages in a session. Measure the cache hit rate from the API usage fields; target 80%+ of input tokens
  cached. If it's low, find what's changing in the prefix (timestamps, ordering, random IDs) and fix it.
- **Off-peak background work:** if DeepSeek offers off-peak discounted pricing (check their pricing page;
  make the window configurable), run all non-urgent jobs (reflection backlog, letters, gardening, chapters,
  snapshot refresh) inside that window, unless he's waiting for one. Weekly letters can be written in the
  off-peak window before Sunday evening and shown at 20:00.
- **No wasted calls:** never reflect a session with fewer than 2 user messages (save a minimal summary
  instead); skip letters with nothing to say (already); don't re-run the "suggested journeys" call unless his
  notes changed; never retry a failed call more than the configured times.
- **Right-size routing:** the first message of a session goes Deep only if it's also long or heavy (not
  automatically). Log how often Deep is used; target well under half of chat messages.
- **Leaner prompts:** keep persona + examples under ~6k tokens; the context block under ~6k in normal use
  (12k hard cap). Don't send "Possibly relevant from the past" when the search finds nothing strong.
- **Output limits:** Fast max_tokens ~1500, Deep as needed for thinking; letters and chapters have their own
  limits matching their target length.
- **Model check-up:** prices fall and new models appear. Add a Developer action "Compare models": runs the
  test bench on another model (entered by name) and shows cost per run next to the current model's, so he
  can switch when something cheaper is just as good.

## Growth tree (real, evidence-based, dynamic)
A living picture of his real growth across his whole life. Every leaf must be earned and evidenced; the AI
proposes after careful consideration over time; he accepts. The tree only grows.

**Data**
- `Milestone(id, type, area, branch, title, description, evidenceJson, confidence, proposedAt, decidedAt,
  status: proposed | accepted | declined | snoozed, snoozeUntil)`.
- `Branch(id, area, name, createdAt)` — sub-branches created by accepted milestones (e.g. studies_career/FAR).

**Evidence stats (computed in code, never by the AI)**
For `prompts/milestone.md` {{STATS}}: per recurring promise/action type, how many planned days and kept days in
the last 14/30/60 days and whether it happened in the last 7; per pattern note, sightings with dates (from
behavior events and reflections), first and last seen, and number of conversations since last seen; active
days; pulse trends; completed journeys with each day's outcome. Group similar promises by normalized text
(and let reflection tag promises with a short `actionKey`, e.g. "study_5pm", for reliable grouping).

**When it runs**
Weekly, in the off-peak window before the Sunday letter, with the Pro model in JSON mode. Also after a
journey's final day. Only "high" confidence proposals are shown. At most 3 proposals per month in total.

**How he sees a proposal**
A card on Talk (and a short mention at the end of the weekly letter): "Something I've noticed" / the title /
the evidence lines / "Add to your tree" (accent) / "Not yet". Not yet = snoozed 30 days. He can also decline
for good ("This isn't right"), which is remembered. Accepting animates the new leaf on the tree (DESIGN.md).
`considered_but_not_yet` is stored and shown nowhere except as context for the next run.

**Removing a leaf:** he can remove any leaf (long-press → Remove, with confirm). Removed leaves are never
re-proposed.

## Adaptive journeys
After every journey session's reflection, run `prompts/journey_adapt.md` (Fast model is fine, JSON mode).
Apply the decision to the remaining days, keep a history of adjustments (`JourneyAdjustment(journeyId, day,
decision, reason, createdAt)`), and pass the latest reason to the next journey session as a runtime flag
("adjusted: …"). Path shows the reason under today's step in meta. `pause` pauses the journey (Path shows
"Paused" with "Resume"). Built-in journey files are never edited; adjustments are stored per journey run.

## Time grounding (QA: wrong greeting, "how did Monday go" before Monday, wrong hour counts)
The model must never do date math. Code does it and hands over finished facts.
- Runtime flag `now` as a sentence: "It's Sunday 4 October 2026, 12:46am (late night; for him it's still
  Saturday night)."
- Every promise, journey day and letter in the context gets code-computed phrases: "due Monday 6 Oct, 8:00am
  (in 31 hours)", "was due Friday 3 Oct, 5:00pm (2 days ago)". Recompute per request (these live in the volatile
  part of the request so the cache prefix stays stable).
- In the API messages (not on screen), prefix each of his messages with a short timestamp "[Sat 3 Oct, 23:12]".
  Session summaries in the context carry dates.
- Greeting bands: 5:00-11:59 "Good morning", 12:00-16:59 "Good afternoon", 17:00-21:59 "Good evening",
  22:00-4:59 "Still up, Umair?" Unit-test the edges.
- Remove `next_opening` everywhere (reflection no longer produces it). The home screen no longer shows an
  AI-written opening line; see DESIGN.md "Talk: home".

## Corrections stick
- When reflection reports corrections, update or resolve the affected entries by id and mark profile values
  "(you confirmed)". Confirmed entries are protected: gardening and reflection may not change them unless he
  corrects them again.
- In What I know, show a small "confirmed" meta tag on entries he has corrected or edited.

## Developer menu (visible)
Make "Developer" a normal, visible row at the bottom of Settings > Advanced (no 7-tap secret; he's the only
user). It contains: Test bench, Health check, Error log, Last crash, Run reflection now, Write test weekly
letter, Write test monthly letter, Fire test reminder, Run memory gardening now, Prompt editor.

## Health check (replaces manual Part B checking)
A screen listing, each with a green/amber/red dot and a plain sentence:
- Last reflection: when, and whether it succeeded; number of sessions waiting for reflection.
- Next weekly letter: scheduled time; last weekly letter: when and success/failure.
- Next monthly letter and last memory gardening.
- Pending reminders count, and whether exact alarms and notifications are allowed.
- Battery optimization: unrestricted or restricted. Autostart reminder for Xiaomi.
- Failed jobs in the last 7 days (count, tap to open error log).
- Memory size: notes, people, events, quotes; possible duplicates (same text or same person name ignoring
  case) with a "Clean up now" button that runs memory gardening.
- Context block size in tokens (warn amber over 12k, red over 20k).
- This month's cost vs budget.
- Last automatic backup.
- Prompt overrides active (if any).
Add a "Copy health report" button.

## Prompt editor (so prompts can be tuned without rebuilding the app)
Developer → Prompt editor: lists every prompt file. Opening one shows its text in a full-screen editor with
Save and "Reset to built-in". Saved versions are stored in the database and used instead of the built-in
asset file. A meta line shows "Edited" or "Built-in". Include an "Export all prompts" button that shares them
as one text file. After saving, suggest running the test bench.

## Memory at scale
- Context block budget: send at most ~40 notes, chosen by confidence, times seen and recency; at most 15
  behavior events; 10 quotes; 20 idea tags. Everything stays in the database; only the context is capped.
- What I know: each section shows its top 5 items with "Show all (N)"; a search field at the top; retired
  notes in a collapsed "Archived" section at the bottom.
- One-time migration in this update: run memory gardening once to rewrite all notes in second person and
  merge duplicate people (e.g. "Father, father").

## Journeys, upgraded
- Completed journeys get a "Done" status with completion date and a one-line takeaway (from the last day's
  reflection summary). They show in a "Completed" group in the journey list and can be restarted.
- "Suggested for you": up to 2 journeys at the top of the list, chosen by matching his active patterns and
  stuck life areas to journey descriptions (ask the Fast model once a week with the list and his context,
  JSON output; cache the result). After a journey is completed, suggest a natural next one.
- "Make one for me": generates a custom 7-day journey with `prompts/journey_custom.md` (Pro model, JSON
  mode), optionally from a request he types ("studying when home is noisy"). Show a preview (name,
  why, the 7 day themes) with "Start" and "Try another". Custom journeys are saved and listed under "Yours".
- The Path tab must observe the database so a step completed in chat shows immediately.

## Test bench
Settings > Advanced > Developer → "Test bench": runs every scenario in `prompts/testbench.md` (off the
record, empty context), showing each reply under its "Expect" line with Pass / Fail
buttons for Umair, and a "Copy report" button (scenario, reply, expect, his verdict). Run sequentially;
show progress; cancellable.

## Model routing (chat)
Two tiers, chosen per message in code (no extra AI call):
- **Deep** (Pro model, thinking mode on): when ANY of these is true: the user message is over ~250
  characters; it contains emotional or heavy words (a constant list in English, e.g. alone,
  worthless, not enough, hate myself, scared, anxious, fail, depressed, hopeless, cry, fight, family, abbu,
  ammi); a mode is active (onboarding, journey, practice, decision,
  untangle); it's the first user message of a session; or the previous reply used Deep
  and the conversation is still on the same topic (stay Deep for the next 3 messages).
- **Fast** (Flash model, non-thinking) otherwise.
- Settings > Advanced: "Always use the deep model" (off by default).
- Thinking output (`reasoning_content`) is never shown or stored. While Deep is thinking, show the typing
  dots with the meta text "thinking…" under them.
- Both tiers use the same messages and cache prefix. Log which tier was used in the usage stats.

## Context additions (in the stable context block)
Also include "Available journeys": the exact names of built-in, custom and suggested journeys, so the coach
can only start real ones.

Snapshot (title, Big Five percents, top values, and the portrait), the last 14 days of pulse check-ins,
and the active journey with today's step.

## What the record shows (computed in code, never by the AI)
Short plain-text lines built from the DB and added to the context block and letter inputs, e.g.:
- Promises: kept 12 of 18 overall, 6 of 7 in the last 30 days. Current run: 4 kept in a row.
- Kept rate by area: studies 9/14, health 2/2, relationships 1/2.
- Kept rate by due time: morning 5/5, evening 6/8, after 11pm 1/5.
- When he talks: mostly 10pm to 1am.
- Tone over the last 2 weeks: from session `tone` values, oldest to newest.
- Mood and energy (from pulse): average this week vs last week; mood on days he kept a promise vs days he
  didn't; mood after late-night sessions vs other days.
Only include lines with enough data (at least 3 items behind a number). Correlations are described plainly,
never as causes.

## Promises saved during chat
The coach adds a hidden line at the end of a reply when he agrees to an action:
`[[promise: text | due: YYYY-MM-DDTHH:MM or none | remind: YYYY-MM-DDTHH:MM or none | why: text]]`
- While streaming, never display anything from `[[` onward; buffer it.
- When the reply completes, parse it (tolerate extra spaces), strip it from the stored message, save the
  promise immediately, and show a small inline confirmation under the reply (see DESIGN.md).
- If `remind` is set: request POST_NOTIFICATIONS (Android 13+) the first time, then schedule an exact alarm
  (SCHEDULE_EXACT_ALARM, falling back to an inexact alarm if not granted). Notification title "Purpose",
  text "It's time: {promise text}". Tapping opens Talk. Reschedule all pending reminders after reboot.
- If parsing fails, save nothing and log the failure (no content in logs).

## Onboarding and snapshot
First launch (after the API key): a short welcome, then the onboarding flow, all skippable and resumable:
1. Values card sort: pick top 5 from ~30 plain-language values (e.g. family, faith, growth, freedom, respect,
   security, adventure, honesty, achievement, service, health, knowledge, creativity, loyalty, independence,
   peace, discipline, fairness, love, recognition, contribution, wealth, courage, humility, friendship, nature,
   tradition, fun, purpose, kindness), then order them.
2. Big Five: the public-domain IPIP Big-Five Factor Markers, 50 items, 5-point scale (verify the exact items
   and keying against ipip.ori.org; don't reproduce from memory if you can't verify). Score as percentages.
   Present 5 items per page with a progress line. Clearly label it "a reflection tool, not a diagnosis".
3. Five onboarding conversations (mode `onboarding`, one per `onboarding_step`), offered one per day on the
   Talk screen as a card ("Getting to know you: 2 of 5 — Your people"). He can also do several in a row.
   **Step completion (QA bug: step 1 ran into step 2's questions; only step 1 was marked done):**
   - A step is marked done when the coach adds the hidden `[[step_done: <step>]]` line (parse and hide like
     other hidden lines), OR when reflection's `onboarding_covered` lists it. Both paths mark steps done,
     so topics covered early or in a normal chat still count.
   - When a step is done: end the onboarding mode for that session (the header line goes away, chat
     continues normally), and show a card under the reply: "Your story: done" / "Next: Your people" with
     "Continue now" (starts the next step as a new session) and "Later".
   - Steps can be completed in any order; the progress count ("2 of 5") always reflects all done steps.
   - One-time fix for his existing data: re-run reflection's `onboarding_covered` check on his past
     onboarding sessions (he answered the people questions inside the story session) and mark those steps done.
4. When all five are done, generate the snapshot with `prompts/snapshot.md` (strong model) and show it.
   Regenerate on request from What I know ("Refresh my snapshot"), at most monthly.

## Modes and tools
- A "+" button in the chat input opens a sheet: Practice a conversation (asks who), Think through a decision,
  Untangle a thought, Start a journey. Choosing one starts a new session in that mode.
- The coach may suggest a mode in chat; if he agrees, the next message switches mode (detect his yes via a
  `[[mode: practice | with: Abbu]]` style line the coach adds after he agrees, parsed like promise lines).
- Validate `[[mode: journey | name: …]]` against existing journeys (case-insensitive). If there's no match,
  don't start anything: open the journey list sheet instead (QA: the coach invented "Quieting Anxiety").
- Journeys: parse `prompts/journeys.md`. One active journey at a time. Each day, the Talk screen shows a card:
  "Break the avoidance loop, day 3: Shrink the start", which starts a session in `journey` mode. Advance the
  day after a journey session completes; never more than one step per calendar day.

## Daily pulse (optional, off by default)
If enabled in Settings, the Talk screen shows a small card once a day: mood (5 steps), energy (5 steps), and
one optional word. Takes ten seconds; dismissible; never a notification. Saved to `Pulse`.

## Read aloud: voices
Add a voice picker (Settings, under Read replies aloud) listing the installed TextToSpeech voices for the
chosen language, preferring high-quality and network voices; plus speed and pitch sliders, and a "Get better
voices" button that opens the system text-to-speech settings (Google's speech services offer more natural
voices to download). Tell him plainly in a meta line: "Phone voices can sound robotic. Try a different voice."

## Read aloud
Setting "Read replies aloud" (off by default) uses Android TextToSpeech for coach replies, with a speaker
button on each coach message to play it on demand. Respect the chosen voice language.

## Core flow 2: Reflection (the learning engine)
A session ends when the user taps "End", or after 30 minutes of inactivity, or on a new day.
Then enqueue a `ReflectionWorker` (retry with backoff; any unreflected session is processed on next launch).

The worker sends the session transcript + current context to `prompts/reflection.md` and expects
**strict JSON** only:

```json
{
  "session_summary": "3-5 sentences, plain language",
  "profile_updates": [{"key": "...", "value": "..."}],
  "people_updates": [{"name": "...", "relation": "...", "notes": "..."}],
  "notes": {
    "add": [{"type": "pattern", "text": "...", "confidence": "guess"}],
    "update": [{"id": 12, "text": "...", "confidence": "likely", "seen_again": true}],
    "resolve": [7]
  },
  "promises": {
    "new": [{"text": "...", "due_date": "YYYY-MM-DD"}],
    "kept": [{"id": 3, "what_happened": "...", "lesson": "..."}],
    "broken": [{"id": 4, "what_happened": "...", "lesson": "..."}],
    "renegotiated": [{"id": 5, "text": "...", "due_date": "YYYY-MM-DD"}],
    "dropped": [6]
  },
  "area_status": [{"area": "studies_career", "status": "stuck", "note": "..."}],
  "behavior_events": [{"when": "...", "situation": "...", "feeling_before": "...", "action": "...", "payoff": "...", "outcome": "..."}],
  "strengths": ["..."],
  "significance": 3,
  "tone": "low, self-critical",
  "his_words": ["exact quote"],
  "ideas_used": ["Stoic dichotomy of control"]
}
```
(`next_opening` was removed: the home screen shows no AI-written opening line.) Phase 3 adds two optional keys, `disagreements` and `contradictions`; code stores one only when his side is found word for word in what he wrote.
Validate the JSON; on parse failure retry once with a "return valid JSON only" nudge, then mark for retry later.
Apply updates in a single DB transaction.

## Core flow 3: Mirror letters
Prompts: `prompts/letter_weekly.md` (weekly) and `prompts/letter_monthly.md` (monthly and yearly,
via {{LETTER_KIND}}). Use the stronger model. Output is plain text; its first line is the letter's title.

- **Weekly:** Sunday 20:00 local time, covering Monday to Sunday. Input: profile, people, notes, the week's
  promises (with lessons), the week's behavior events, "What the record shows", last week's letter, recent ideas, and the week's conversations: full transcripts, sorted by
  significance, up to a budget of ~120k tokens; any session beyond the budget is sent as its summary.
  Skip if there was no meaningful conversation that week.
- **Monthly:** 1st of the month, covering the previous month. Input: everything above (minus last week's
  letter), plus that month's weekly letters as {{SUB_LETTERS}}, all summaries, all `his_words`, area
  statuses, and full transcripts of the most significant sessions within the same ~120k token budget.
- **Yearly:** Jan 1, using the monthly letters as {{SUB_LETTERS}}, all summaries and `his_words`.
- All jobs: WorkManager, with catch-up on app launch if missed; never two letters for the same period;
  period starts no earlier than his first session.
- A "meaningful conversation" = a reflected session where he sent at least 4 messages.

## Screens
- **Talk**: streaming chat, "just listen" toggle, "End conversation" button.
- **Mirror**: weekly, monthly and yearly letters; unread dots; open to read; delete. See DESIGN.md.
- **Path**: growth tree preview at the top (opens full screen), then the active journey, then promises.
- **What I know**: profile, people, notes, area statuses — all readable, editable, deletable by the user.
- **Settings**: API key, provider/model, tough-love level, encrypted backup export/import (passphrase).

## Prompt files (`app/src/main/assets/prompts/`)
(Blueprint V3 rewrote the 18 prompts with his approval; see CHANGES-v2.md.) Supplied by Umair: `examples.md`, `testbench.md`, `gardening.md`, `persona.md`, `reflection.md`, `letter_weekly.md`, `letter_monthly.md`, `snapshot.md`,
`journeys.md`, and `mode_*.md`. Do not edit them.
If you think a prompt needs a change, propose it in plain language and wait for his OK.
The code fills the `{{PLACEHOLDERS}}` in these files at runtime.

## Repo starting state
The repo starts with only this file, `README.md`, `.gitignore`, and the prompt files under
`app/src/main/assets/prompts/`. Create the Gradle project around them; don't move or overwrite the prompts.

## Build phases (finish and verify each before the next)
1. **Skeleton**: app shell, navigation, encrypted DB, Settings with API key, Talk screen with streaming chat
   using `persona.md`. Done when: a real conversation works and survives app restart.
2. **Memory + reflection**: entities, context assembly, ReflectionWorker, What I know screen.
   Done when: a fact said in chat 1 shows up in What I know and is used in chat 2.
3. **Promises**: extraction via reflection, Promises screen, one-due-promise injection.
4. **Mirror reports**: monthly/yearly/on-demand.
5. **Backup + polish**: encrypted export/import, Get Help screen, UI polish.

## Working style
- Small commits, one feature at a time. Explain what you changed in plain language.
- Ask before adding a new dependency not listed above.
- Write unit tests for: context assembly, reflection JSON parsing/applying, promise state changes.
- If something in this file conflicts with what Umair asks in the moment, ask him which wins.

## Current plan
See BLUEPRINT-V3.md for the phased build plan. Read it before making changes.

## Blueprint V3 additions (Phases 0 to 6): what the code now does
This section is the current truth where it differs from older text above.
- **Evidence rules.** Only his own words and actions are evidence. A coach claim never raises confidence or times-seen
  (`MemoryRepository.synthesizeMemory`). A journey day, promise or step is complete only when he did it or the coach
  records it with an action. Quotes shown as his words are verified verbatim (`memory/QuoteCheck.kt`).
- **Retrieval (Phase 2).** FTS4 plus on-device embeddings (all-MiniLM-L6-v2 through ONNX Runtime, Apache-2.0, see
  `docs/EMBEDDING_MODEL.md`), merged by rank fusion and reranked by recency and evidence grade. Embeddings only find
  and flag; they never write memory or change confidence. Notes and profile lines are bitemporal (`validFrom`,
  `validTo`, `recordedAt`); a changed fact is retired, not deleted, and shows as "used to be true".
- **Ledgers (Phase 3), all counted in code.** Values-versus-kept-promises per week (`ledger/ValuesLedger`), the
  disagreement ledger and contradiction register (`ledger/LedgerRules`), and a durable action journal (`action_log`,
  `ActionJournal`). They reach the chat context and letters through `MemoryRepository.record()` / `{{RECORD}}`.
- **Phone screen time (Phase 4, opt-in, off by default).** `PACKAGE_USAGE_STATS` usage access, minutes per day, hour and
  app category only (never app names), stored in the encrypted DB (`screen_usage`), viewable, exportable and deletable;
  turning it off deletes it. Two summary lines go to the AI provider with the rest of the context.
- **Per-job models (Phase 5).** Reflection, letters, chapters, gardening and snapshot each have a model choice
  (`job_model` table, `ai/JobModels`); no row means the main deep model, as before. Chat keeps its Fast/Deep routing.
  Settings shows each job's cost and a projection on the chosen model's entered prices.
- **Database and backups.** Room version 15 (migrations 1 to 15, schemas exported, `MigrationTest`). Backups are
  `PURPOSE1 | salt | iterations | iv | AES-256-GCM(gzip(json))` with the key from PBKDF2-HMAC-SHA256 at **600,000
  iterations** (OWASP's current figure; Phase 6). The iteration count is stored in the file header, so backups made at the
  older 210,000 still restore, and new backups use the new count: that is the migration path. The database key itself
  is 32 random bytes held in the Keystore, not derived from a passphrase, so it needs no iteration count.
- **Scoreboard.** `tools/eval/` (offline, not in the APK) scores the test bench, a memory exam and honesty checks.
