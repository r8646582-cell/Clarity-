"""Port of memory/RelevantMemories.kt + SearchDocs/SearchIndex (the Phase 1 BASELINE retrieval). FTS4 runs on the
same SQLite engine family the app uses (Room @Fts4). It is a mirror: if the Kotlin changes, change this and re-run the
scoreboard. One deliberate difference: ties on `day` are broken by rowid so results are deterministic offline.

Phase 2 will add a second retriever with the same interface (`retrieve(message) -> list[Hit]`) to compare."""
import re
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime

from .prompt import ZONE

MAX_RESULTS, MAX_TERMS, CANDIDATES, MAX_CHARS = 5, 8, 60, 320
# Mirrors the app's STOP list. Phase 2: Roman Urdu removed (English only). feel/want/need/think/know stay: dropping them
# scored slightly worse on the memory exam (0.708 -> 0.699), so there is no evidence for changing them.
STOP = set("""the and for that this with you your are was were have has had but not what when where why how who can could
would should will just like really about from they them their there then than been being into out all any some get got
did does doing done its it's i'm im dont don't cant can't know think feel feeling want need today yesterday tomorrow now
still also very much more most lot lots thing things something anything nothing everything okay yeah yes hmm one two time
day days again even only too him her his she our off over said say says going gonna make made""".split())


@dataclass
class Hit:
    kind: str
    ref: str  # as the app stores it: "3" (summary), "3:12" (quote/event), "n7" ...
    day: str
    text: str

    def eval_id(self) -> str:
        """Maps an archive hit back to the fixture's evidence ids (s:/q:/e:/n:/p:)."""
        if self.kind == "summary":
            return f"s:{self.ref}"
        if self.kind in ("quote", "event"):
            return f"{self.kind[0]}:{self.ref.split(':')[1]}"
        if self.ref.startswith("n"):
            return f"n:{self.ref[1:]}"
        if self.ref.startswith("p"):
            return f"p:{self.ref[1:]}"
        return f"{self.kind}:{self.ref}"


def terms(message: str):
    words = re.findall(r"(?:[^\W_]|')+", message.lower())
    out = []
    for w in (x.strip("'") for x in words):
        if len(w) >= 3 and w not in STOP and any(c.isalpha() for c in w) and w not in out:
            out.append(w)
    return sorted(out, key=lambda t: -len(t))[:MAX_TERMS]  # stable, like sortedByDescending


def match_query(ts):
    parts, seen = [], set()
    for t in ts:
        for piece in t.split("'"):
            piece = "".join(c for c in piece if c.isalnum())
            if len(piece) >= 3 and piece not in seen:
                seen.add(piece)
                parts.append(piece)
    return " OR ".join(f"{p}*" for p in parts) if parts else None


def score(text: str, ts) -> int:
    words = re.findall(r"[^\W_]+", text.lower())
    return sum(1 for t in ts if any(w.startswith("".join(c for c in t if c.isalnum())) for w in words))


def rank(hits, ts, exclude_session=None):
    if not ts:
        return []
    need = 1 if len(ts) == 1 else 2
    scored = []
    for h in hits:
        if exclude_session is not None and h.kind in ("summary", "quote", "event") and h.ref.split(":")[0] == str(exclude_session):
            continue
        s = score(h.text, ts)
        if s >= need:
            scored.append((h, s))
    scored.sort(key=lambda p: p[0].day, reverse=True)
    scored.sort(key=lambda p: -p[1])
    out, seen = [], set()
    for h, _ in scored:
        key = h.text.strip().lower()
        if key not in seen:
            seen.add(key)
            out.append(h)
    return out[:MAX_RESULTS]


def rank_notes(notes, ts):
    cand = [(n, score(n["text"], ts)) for n in notes if n.get("status") == "active"]
    cand = [(n, s) for n, s in cand if s > 0]
    cand.sort(key=lambda p: (-p[1], -p[0]["lastSeen"], -p[0]["id"]))
    return [n for n, _ in cand][:MAX_RESULTS]


def _day(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, ZONE).date().isoformat()


def _event_text(e) -> str:
    bits = [e.get("situation"), f"felt {e['feelingBefore']}" if e.get("feelingBefore") else None,
            f"→ {e['action']}" if e.get("action") else None, f"→ payoff: {e['payoff']}" if e.get("payoff") else None,
            f"→ {e['outcome']}" if e.get("outcome") else None]
    return " ".join(b for b in bits if b)


def search_docs(data):
    docs = []
    for s in data["sessions"]:
        if s.get("summary"):
            docs.append(("summary", str(s["id"]), _day(s["startedAt"]), ". ".join(x for x in (s.get("title"), s["summary"].strip()) if x)))
    for q in data.get("quotes", []):
        docs.append(("quote", f"{q['sessionId']}:{q['id']}", _day(q["createdAt"]), f"\"{q['text'].strip()}\""))
    for e in data.get("behaviorEvents", []):
        if not e.get("deletedByUser"):
            docs.append(("event", f"{e['sessionId']}:{e['id']}", _day(e["createdAt"]), _event_text(e)))
    for l in data.get("letters", []) + data.get("chapters", []):
        docs.append(("letter", str(l["id"]), l.get("periodEnd", ""), f"{l.get('title', '')}. {l.get('content', '')}"))
    for n in data.get("notes", []):
        if n.get("status") == "retired":
            docs.append(("note", f"n{n['id']}", _day(n["lastSeen"]), n["text"]))
    for p in data.get("profile", []):
        if p.get("retired") and not p.get("deletedByUser") and p.get("value"):
            docs.append(("note", f"p{p['key']}", _day(p["updatedAt"]), f"{p['key']}: {p['value']}"))
    return docs


class BaselineRetriever:
    name = "baseline-fts4-v1"

    def __init__(self, data):
        self.data = data
        self.db = sqlite3.connect(":memory:", check_same_thread=False)
        self._lock = threading.Lock()
        self.db.execute("CREATE VIRTUAL TABLE search_doc USING fts4(kind, refId, day, text, notindexed=kind, notindexed=refId, notindexed=day)")
        self.db.executemany("INSERT INTO search_doc(kind, refId, day, text) VALUES (?,?,?,?)", search_docs(data))

    def retrieve(self, message: str, exclude_session=None):
        ts = terms(message)
        q = match_query(ts)
        if not q:
            return [], []
        with self._lock:
            rows = self.db.execute("SELECT kind, refId, day, text FROM search_doc WHERE search_doc MATCH ? ORDER BY day DESC, rowid DESC LIMIT ?",
                                   (q, CANDIDATES)).fetchall()
        hits = rank([Hit(*r) for r in rows], ts, exclude_session)
        return hits, rank_notes(self.data.get("notes", []), ts)


def block(hits):
    if not hits:
        return None
    label = {"summary": "a conversation", "quote": "he said", "event": "a moment", "letter": "a letter",
             "chapter": "a chapter", "note": "an older note"}

    def clip(s):
        t = re.sub(r"\s+", " ", s).strip()
        return t if len(t) <= MAX_CHARS else t[:MAX_CHARS].rsplit(" ", 1)[0] + "…"
    return ("Possibly relevant from the past (from your archive; mention only if it helps):\n" +
            "\n".join(f"- {h.day}, {label.get(h.kind, h.kind)}: {clip(h.text)}" for h in sorted(hits, key=lambda h: h.day)))


def insights(notes):
    if not notes:
        return None
    return "RELEVANT INSIGHTS & REALIZATIONS:\n" + "\n".join(f"- {n['text']} [{n['confidence']}]" for n in notes[:MAX_RESULTS])


# ---- Phase 2: hybrid retriever (mirror of RelevantMemories.rankHybrid + HybridRetrieval + EmbeddingIndex) -------------
RRF_K, MIN_SIMILARITY, SEMANTIC_LIMIT, HALF_LIFE_DAYS = 60, 0.45, 10, 180.0


def fuse(lists, k=RRF_K):
    score = {}
    for lst in lists:
        seen = []
        for x in lst:
            if x not in seen:
                seen.append(x)
        for i, x in enumerate(seen):
            score[x] = score.get(x, 0.0) + 1.0 / (k + i + 1)
    return sorted(score.items(), key=lambda p: -p[1])  # stable: ties keep first-list order


def rank_hybrid(lexical, semantic, ts, exclude_session, now_ms):
    if not semantic:
        return rank(lexical, ts, exclude_session)
    import math
    key = lambda h: f"{h.kind}|{h.ref}"
    by = {}
    lex = [h for h in rank_all(lexical, ts, exclude_session)]
    for h in lex:
        by.setdefault(key(h), h)
    sem = []
    for h, _ in semantic:
        if exclude_session is not None and h.kind in ("summary", "quote", "event") and h.ref.split(":")[0] == str(exclude_session):
            continue
        by.setdefault(key(h), h)
        sem.append(key(h))
    fused = fuse([[key(h) for h in lex], sem])

    def adj(item):
        k, sc = item
        try:
            t = datetime.fromisoformat(by[k].day).replace(tzinfo=ZONE).timestamp() * 1000
        except ValueError:
            t = 0
        age = max(0.0, (now_ms - t) / 86_400_000.0)
        return sc * (1.0 + 0.15 * math.exp(-age * math.log(2) / HALF_LIFE_DAYS))
    out, seen = [], set()
    for k, _ in sorted(fused, key=adj, reverse=True):
        h = by[k]
        t = h.text.strip().lower()
        if t not in seen:
            seen.add(t)
            out.append(h)
    return out[:MAX_RESULTS]


def rank_all(hits, ts, exclude_session=None):
    """rank() without the MAX_RESULTS cut (Kotlin: rank(limit = Int.MAX_VALUE))."""
    global MAX_RESULTS
    keep, MAX_RESULTS = MAX_RESULTS, 10**9
    try:
        return rank(hits, ts, exclude_session)
    finally:
        MAX_RESULTS = keep


class HybridRetriever(BaselineRetriever):
    name = "hybrid-fts4+minilm-v1"

    def __init__(self, data, embedder=None, now_ms=None, min_similarity=None):
        super().__init__(data)
        from . import embed
        from .prompt import DEFAULT_NOW
        self.emb = embedder or embed.Embedder()
        self.now_ms = now_ms or DEFAULT_NOW.timestamp() * 1000
        self.min_sim = MIN_SIMILARITY if min_similarity is None else min_similarity
        self.docs = [Hit(*d) for d in search_docs(data)]
        self.vecs = [self.emb.embed(d.text) for d in self.docs]
        # Live (active) notes get vectors too, like EmbeddingIndex's LIVE_NOTE documents.
        self.live = [n for n in data.get("notes", []) if n.get("status") == "active"]
        self.live_vecs = [self.emb.embed(n["text"]) for n in self.live]

    def retrieve(self, message: str, exclude_session=None):
        ts = terms(message)
        q = match_query(ts)
        lexical = []
        if q:
            with self._lock:
                rows = self.db.execute("SELECT kind, refId, day, text FROM search_doc WHERE search_doc MATCH ? ORDER BY day DESC, rowid DESC LIMIT ?",
                                       (q, CANDIDATES)).fetchall()
            lexical = [Hit(*r) for r in rows]
        qv = self.emb.embed(message)
        sims = sorted(((d, float(qv @ v)) for d, v in zip(self.docs, self.vecs)), key=lambda p: -p[1])
        semantic = [(d, s) for d, s in sims if s >= self.min_sim][:SEMANTIC_LIMIT]
        if not ts and not semantic:
            return [], []
        related = [(n, s) for n, s in sorted(((n, float(qv @ v)) for n, v in zip(self.live, self.live_vecs)), key=lambda p: -p[1])
                   if s >= self.min_sim][:SEMANTIC_LIMIT]
        return (rank_hybrid(lexical, semantic, ts, exclude_session, self.now_ms),
                rank_notes_hybrid(self.data.get("notes", []), related, ts, self.now_ms))


def rank_notes_hybrid(notes, related, ts, now_ms):
    """Mirror of RelevantMemories.rankNotesHybrid: keyword + meaning matches among active notes, RRF, recency/grade nudge."""
    import math
    if not related:
        return rank_notes(notes, ts)
    lex = [n for n in notes if n.get("status") == "active" and score(n["text"], ts) > 0]
    lex.sort(key=lambda n: (-score(n["text"], ts), -n["lastSeen"], -n["id"]))
    by = {n["id"]: n for n in lex}
    sem = []
    for n, _ in related:
        by.setdefault(n["id"], n)
        sem.append(n["id"])
    grades = ["guess", "likely", "confirmed"]

    def adj(item):
        i, sc = item
        n = by[i]
        age = max(0.0, (now_ms - n["lastSeen"]) / 86_400_000.0)
        g = max(0, grades.index(n["confidence"])) if n.get("confidence") in grades else 0
        return sc * (1.0 + 0.15 * math.exp(-age * math.log(2) / HALF_LIFE_DAYS) + 0.10 * g / 2.0)
    out, seen = [], set()
    for i, _ in sorted(fuse([[n["id"] for n in lex], sem]), key=adj, reverse=True):
        t = by[i]["text"].strip().lower()
        if t not in seen:
            seen.add(t)
            out.append(by[i])
    return out[:MAX_RESULTS]
