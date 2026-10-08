# UPDATE 1 — fixes from first real use

Umair tested the app. Problems: replies came out garbled (broken words and nonsense mixed languages),
replies feel shallow and too short, the UI looks cheap and default, letters can't be deleted, and letters
should arrive on their own. This update fixes all of it. Do the tasks in order, commit after each, and
tell Umair in plain language what changed.

New/updated files in this update (already in the repo, don't edit the prompts):
- `app/src/main/assets/prompts/persona.md`: rewritten for depth and wisdom.
- `app/src/main/assets/prompts/reflection.md`: now returns `next_opening`.
- `app/src/main/assets/prompts/report.md`: title line, data threshold, period start rule.
- `DESIGN.md`: the full visual spec. Follow it exactly.

## 1. Fix garbled replies (do this first)
Likely cause: the streaming (SSE) parser losing or corrupting pieces when a network chunk splits a line or
a multi-byte character. Also check sampling settings.
- Read the stream line by line with a proper buffered UTF-8 reader (e.g. Okio `BufferedSource.readUtf8Line()`).
  Never decode raw byte chunks to strings directly. Only parse complete `data:` lines; skip blank lines and
  `:` keep-alive comments; stop at `[DONE]`.
- Append only `choices[0].delta.content`. Never append `reasoning_content` or anything else to the message.
- Save the coach message to the DB only after the stream completes. If the stream fails midway, show
  "Couldn't finish that reply." with a Retry button and do not save the partial text.
- Check the request body against DeepSeek's current docs, especially the thinking on/off parameter for the
  chat model. Chat must run in non-thinking mode.
- Change the default chat temperature to 0.7.
- Add a unit test that feeds a recorded SSE response split at random byte positions (including inside Urdu
  characters) and asserts the reassembled text is exact.
Done when: 20 test messages in a row, including Roman Urdu ones, produce clean replies.

## 2. Make replies deep enough
- Make sure chat `max_tokens` is at least 1500 and nothing in the code trims or shortens replies.
- The new persona.md handles depth; just confirm it's loaded fresh (not a cached old copy).
Done when: a heavy message like "I'm wasting my time and don't know if I'm enough" gets a 2-4 paragraph reply
that offers real insight before asking anything.

## 3. Opening line
- Store `next_opening` from each reflection (a single "latest opening" value).
- On a new session, show it on the Talk screen as described in DESIGN.md. Add it to the chat history as the
  first assistant message once he sends his first message, so the coach remembers saying it.
- Fallback when empty: "Good evening. What's on your mind?" (time-based greeting).

## 4. Letters: autonomous + deletable
- Remove the "Generate now" button. Letters are written automatically:
  - Monthly: a WorkManager job on the 1st covering the previous month. If the phone was off or the job missed,
    run it on next app launch (catch-up). Never write two letters for the same period.
  - Yearly: on Jan 1, covering the previous year.
  - Skip writing entirely if there were zero meaningful conversations in the period.
- Period start = the later of the period start and his first-ever session.
- Add "Write a letter now" to the Mirror overflow menu, disabled until there are 3+ meaningful conversations
  since the last letter.
- Delete: long-press a letter row, or ⋯ → Delete letter in the letter view. Always confirm.
- The first line of the report text is the letter's title. Show it as described in DESIGN.md.
- A "meaningful conversation" = a reflected session where he sent at least 4 messages.

## 5. Redesign the whole app per DESIGN.md
Replace the default Material look everywhere: colors, fonts, nav bar, header, chat layout, Mirror,
Promises, What I know, Settings, empty states, app icon and splash. Night theme is default.
Done when: no default Material purple/lavender remains, both fonts are in use, and every screen matches
the wireframes and rules in DESIGN.md.

## 6. Settings cleanup
Reorganize Settings exactly as in DESIGN.md. Move provider, models, temperature, thinking switch, prices and
detailed usage under a collapsed "Advanced" section. Prefill prices with current DeepSeek prices (check
their pricing page if you can; otherwise use sensible defaults, editable).

## 7. Session ending
Confirm sessions end automatically after 30 minutes of inactivity or on a new day, then reflect in the
background. "End conversation" stays in the ⋯ menu for when he wants to close a talk himself.
