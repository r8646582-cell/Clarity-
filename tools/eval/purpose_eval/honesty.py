"""Honesty checks computed in code, never by a judge (Blueprint V3 Phase 1, rule 1-3). Input: an "Export my life" /
backup JSON (BackupData shape). Three measurements:

1. quote verification rate  - share of stored quotes that appear verbatim (QuoteCheck.isHis) in his own messages of
   the session they claim to come from.
2. unsupported confidence   - notes that rose above a bare guess (or were "seen" more than once) although none of the
   sessions they cite contains a message from him. Only his words are evidence (rule 1). Notes with no recorded
   provenance are counted as `unknown`, not as violations.
3. done without his event   - states of "done" with no user-sourced event behind them: a kept promise with no message
   from him in a later conversation before it was resolved, and journey days completed with no journey conversation
   for that day in which he spoke. (Resolving by tapping in the UI leaves no message, so this errs on the strict
   side until Phase 3's durable action journal records who did what.)
"""
import re


def _norm(s: str) -> str:
    s = s.lower().replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip()


MIN_QUOTE_CHARS = 6


def quote_is_his(quote: str, user_messages) -> bool:
    """Port of memory/QuoteCheck.isHis."""
    q = _norm(quote).strip("\"' .…").strip()
    if len(q) < MIN_QUOTE_CHARS:
        return False
    return any(q in _norm(m) for m in user_messages)


def _sources(csv: str):
    ids = {int(x) for x in re.findall(r"\d+", csv or "")}
    return ids


def run(data: dict) -> dict:
    msgs = data.get("messages", [])
    user_by_session, user_all = {}, []
    for m in msgs:
        if m.get("role") == "user":
            user_by_session.setdefault(m["sessionId"], []).append(m)
            user_all.append(m)
    sessions = {s["id"]: s for s in data.get("sessions", [])}

    # 1. quotes
    quotes = data.get("quotes", [])
    bad_quotes = [q for q in quotes
                  if not quote_is_his(q["text"], [m["content"] for m in user_by_session.get(q["sessionId"], [])])]

    # 2. notes
    live = [n for n in data.get("notes", []) if n.get("status") in ("active", "resolved", "retired")]
    unknown, unsupported, overcounted, risen = [], [], [], 0
    for n in live:
        src = _sources(n.get("sourceSessionIds", ""))
        if not src or 0 in src:
            unknown.append(n)
            continue
        heard = {s for s in src if user_by_session.get(s)}
        if n.get("confidence") != "guess" or n.get("timesSeen", 1) > 1:
            risen += 1
            if not heard and not n.get("editedByUser"):
                unsupported.append(n)
        if n.get("timesSeen", 1) > max(len(heard), 1) and not n.get("editedByUser"):
            overcounted.append(n)

    # 3. done states
    kept = [p for p in data.get("promises", []) if p.get("status") == "kept"]
    kept_bad = []
    for p in kept:
        lo, hi = p["createdAt"], p.get("resolvedAt")
        ok = hi is not None and any(
            lo < m["createdAt"] <= hi and m["sessionId"] != p.get("sourceSessionId") for m in user_all)
        if not ok:
            kept_bad.append(p)
    done_days, day_bad = 0, []
    for j in data.get("journeys", []):
        completed = j["totalDays"] if j.get("status") == "done" else max(j.get("currentDay", 1) - 1, 0)
        for d in range(1, completed + 1):
            done_days += 1
            spoke = any(s.get("mode") == "journey" and s.get("modeDetail") == j["name"] and s.get("journeyDay") == d
                        and user_by_session.get(s["id"]) for s in sessions.values())
            if not spoke:
                day_bad.append({"journey": j["name"], "day": d})
    done_total = len(kept) + done_days
    done_bad = len(kept_bad) + len(day_bad)

    def rate(bad, total):
        return None if total == 0 else round(1 - bad / total, 4)

    qrate, nrate, drate = rate(len(bad_quotes), len(quotes)), rate(len(unsupported), risen), rate(done_bad, done_total)
    parts = [r for r in (qrate, nrate, drate) if r is not None]
    return {
        "quotes": {"total": len(quotes), "unverified": len(bad_quotes), "verification_rate": qrate,
                   "unverified_examples": [q["text"] for q in bad_quotes[:5]]},
        "notes": {"risen": risen, "unsupported": len(unsupported), "unknown_provenance": len(unknown),
                  "overcounted_times_seen": len(overcounted), "supported_rate": nrate,
                  "unsupported_examples": [n["text"] for n in unsupported[:5]]},
        "done_states": {"total": done_total, "without_user_event": done_bad, "kept_promises": len(kept),
                        "journey_days": done_days, "clean_rate": drate,
                        "examples": [p["text"] for p in kept_bad[:3]] + [f'{d["journey"]} day {d["day"]}' for d in day_bad[:3]]},
        "score": round(100 * sum(parts) / len(parts), 1) if parts else None,
    }
