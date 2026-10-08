# UPDATE 9 — replies survive leaving the app

Bug: Umair left the app mid-reply (didn't close it from recents) and the reply failed with an error.
The reply is tied to the screen; when Android pauses or stops the UI, the request dies. His Redmi phone also
kills background apps aggressively.

**First:** complete UPDATE-1 to UPDATE-8 if not done. Merge CLAUDE.md with your existing version.
Where this conflicts with UPDATE-1's "save only after the stream completes", this update wins.

## 1. Move generation out of the UI
Implement CLAUDE.md "Replies survive leaving the app": foreground service (`shortService` on Android 14+),
streaming into the database, UI observing the database, interrupted state with Try again.
Done when: he sends a heavy message, switches to another app for 30 seconds, comes back, and the full reply
is there. Also: send, lock the phone, unlock a minute later: reply complete. And: force-stop mid-reply,
reopen: partial reply with "Reply interrupted" and a working Try again.

## 2. Keep Purpose reliable
Implement CLAUDE.md "Keep Purpose reliable on Xiaomi/Redmi" (Settings section and one-time Talk card).
Done when: the battery status line shows correctly and both buttons open the right system screens on his phone.

Add both tests to TESTING.md under "Stress tests".
