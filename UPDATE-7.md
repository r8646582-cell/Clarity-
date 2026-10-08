# UPDATE 7 — conversations you can return to (like Claude)

Umair wants to go back to any previous conversation and continue it, like in the Claude app. Purpose keeps
one shared memory, but conversations become separate threads with a chat list.

**First:** complete UPDATE-1 to UPDATE-6 if not done. This update replaces UPDATE-6's read-only
"Past conversations" with the full version below. Overwrite prompt files; merge CLAUDE.md and DESIGN.md.
Updated prompt: `reflection.md` (adds `title` and continued-conversation handling).

## 1. Data
Add `title` and `reflectedUpToMessageId` to Session, with a migration. Backfill titles for existing sessions
from the first words of his first message (or the summary's first line).

## 2. Conversations drawer
Build the drawer per CLAUDE.md "Conversations" and DESIGN.md "Conversations drawer": new conversation,
local search, date groups, titles, rename, forget.

## 3. Continue any conversation
Opening a past conversation lets him keep talking in it. Implement the incremental reflection with the
"ALREADY REFLECTED" marker so nothing is learned or promised twice.
Done when: he can end a chat, start a new one, go back to the first one, continue it, and after it ends,
What I know and Promises have no duplicates.

## 4. Forget and Erase everything
Per CLAUDE.md. Then remind Umair to clean out his QA test conversations.
