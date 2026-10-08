# UPDATE 11 — smooth streaming and keyboard

Umair: "When I press send, the keyboard doesn't close. The answer appears abruptly, with lag, in chunks.
It should flow smoothly, line by line. Not slow, but smooth."

**First:** complete UPDATE-1 to UPDATE-10 if not done. Merge CLAUDE.md and DESIGN.md.

## 1. Close the keyboard on send
On send: `focusManager.clearFocus()` and `keyboardController?.hide()`. The input clears; tapping it reopens
the keyboard. Then scroll as described in DESIGN.md (his message near the top).

## 2. Find the real cause of the chunky text
Likely causes, check all and fix:
- **The UI reads from the database,** which is only written about once a second (UPDATE-9). That alone
  makes text appear in one-second jumps. Fix per CLAUDE.md: the UI reads live text from an in-memory
  StateFlow; the database is for persistence only.
- **Network bursts:** DeepSeek sends text in irregular bursts. Even with a perfect pipe, raw chunks look jerky.
- **Recomposition cost:** if every chunk recomposes or re-measures the whole message list, frames drop.
- **Auto-scroll fighting the text:** animated scrolls on every chunk cause stutter.
- **Debug build:** debug builds are much slower; always judge smoothness on the release build.

## 3. Smooth reveal (the method)
Separate "received text" from "displayed text":
- Received text: everything that has arrived from the stream (in memory).
- Displayed text: what's on screen. A frame-driven loop (`withFrameNanos`) reveals more of the received
  text every frame, at whole-word boundaries.
- Adaptive speed: reveal rate depends on how far behind the display is. Base rate about 60 characters per
  second; when the backlog grows, speed up so the display never falls more than ~0.4 seconds behind
  (catch up smoothly, never jump). When the stream completes, finish the remaining backlog within ~0.5s.
- Newly revealed words fade in over ~120ms (alpha only; no movement), so lines grow softly instead of
  popping. If that's too costly, a plain smooth reveal without fade is acceptable.
- Never re-animate text that's already shown. Reopening a chat shows complete messages instantly.
- Hidden `[[…]]` lines are cut from received text before it reaches the display.
- Respect the system "remove animations" setting: reveal instantly in that case.

## 4. Keep frames cheap
- The streaming message is its own composable with a stable key; other messages don't recompose.
- Split the streaming text into paragraphs; only the last paragraph changes, so earlier paragraphs
  aren't re-laid-out each frame.
- No animated scrolling during streaming: while following, use instant scroll adjustments (or
  `requestScrollToItem`) at most once per frame, and stop following when the reply reaches the screen
  bottom (DESIGN.md).
- Measure with the release build and Android's "Profile HWUI rendering" bars: no regular red spikes.

Done when, on his Redmi with the release build: pressing send closes the keyboard, and a long deep reply
flows in as an even, readable stream with no visible jumps, from first word to last.
