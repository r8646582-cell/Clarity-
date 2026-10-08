"""The memory exam: a fixed synthetic history (fixtures/history.json, invented and clearly fake) and ~40 questions
(fixtures/memory_exam.json) covering recall, promises/time, synthesis, superseded facts and trap questions.

Two scores:
  retrieval - computed in code, no model: for each answerable question, how much of its gold evidence the coach could
              see (the stable context block + what retrieval pulls in for that question).
  answers   - the coach answers each question from that context; an LLM judge grades against the gold answer."""
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from . import FIXTURES, llm
from . import prompt as P
from . import retrieval as R

NOW = datetime(2026, 10, 4, 14, 0, tzinfo=P.ZONE)
EVENTS_IN_CHAT, QUOTES_IN_CHAT, RECENT_RESOLVED, SUMMARIES = 15, 10, 10, 5


def load():
    history = json.loads((FIXTURES / "history.json").read_text(encoding="utf-8"))
    exam = json.loads((FIXTURES / "memory_exam.json").read_text(encoding="utf-8"))
    return history, exam["questions"]


def _d(ms):
    return R._day(ms)


def render_context(data):
    """Port of ContextFormatter.chatContext + recentSummaries for the sections the fixture has. Returns
    (context_text, summaries_text, ids) where ids are the evidence ids visible in the stable block."""
    ids, sections = set(), []

    def section(title, lines):
        if lines:
            sections.append(title + ":\n" + "\n".join(l for l, _ in lines))
            ids.update(i for _, i in lines if i)

    prof = sorted((p for p in data["profile"] if not p.get("retired") and not p.get("deletedByUser")), key=lambda p: p["key"])
    section("Who he is", [(f"- {p['key']}: {p['value']}", f"p:{p['key']}") for p in prof])
    section("His strengths", [(f"- {s['text']}", f"st:{s['id']}") for s in data.get("strengths", []) if not s.get("retired")])
    section("People in his life", [(f"- {p['name']}" + (f" ({p['relation']})" if p.get("relation") else "") +
                                    (f": {p['notes']}" if p.get("notes") else ""), f"person:{p['id']}") for p in sorted(data["people"], key=lambda p: p["name"].lower())])
    active = sorted((n for n in data["notes"] if n["status"] == "active"), key=lambda n: n["id"])
    for typ, title in (("pattern", "Patterns (a guess is only a guess)"), ("what_helps", "What works for him"),
                       ("what_doesnt", "What doesn't work for him"), ("thread", "Open threads")):
        section(title, [(f"- [{n['confidence']}, seen {n['timesSeen']}x] {n['text']}", f"n:{n['id']}") for n in active if n["type"] == typ])
    evs = sorted(data["behaviorEvents"], key=lambda e: -e["createdAt"])[:EVENTS_IN_CHAT]
    section("Recent behavior, newest first", [(f"- {_d(e['createdAt'])}, {e['whenText']}: {R._event_text(e)}", f"e:{e['id']}") for e in evs])
    qs = sorted(data["quotes"], key=lambda q: -q["createdAt"])[:QUOTES_IN_CHAT]
    section("Things he said that matter", [(f"- {_d(q['createdAt'])}: \"{q['text']}\"", f"q:{q['id']}") for q in qs])
    opens = sorted((p for p in data["promises"] if p["status"] == "open"), key=lambda p: p["id"])
    section("Open promises", [(f"- id {p['id']}: {p['text']} (due {(p['dueAt'] or 'no date').replace('T', ' ')})" +
                               (f". Why: {p['why']}" if p.get("why") else ""), f"pr:{p['id']}") for p in opens])
    done = sorted((p for p in data["promises"] if p["status"] in ("kept", "broken")),
                  key=lambda p: (-(p.get("resolvedAt") or p["createdAt"]), -p["id"]))[:RECENT_RESOLVED]
    section("Recently kept or broken, with what they taught",
            [(f"- {p['status']}: {p['text']}" + (f". What happened: {p['whatHappened']}" if p.get("whatHappened") else "") +
              (f". Lesson: {p['lesson']}" if p.get("lesson") else ""), f"pr:{p['id']}") for p in done])
    ctx = "What you know about the user so far:\n\n" + "\n\n".join(sections)
    recent = sorted((s for s in data["sessions"] if s.get("summary")), key=lambda s: s["startedAt"])[-SUMMARIES:]
    ids.update(f"s:{s['id']}" for s in recent)
    summ = "Your last conversations:\n" + "\n".join(f"{_d(s['startedAt'])}: {s['summary'].strip()}" for s in recent)
    return ctx, summ, ids


def retrieval_score(data, questions, retriever=None):
    """No model involved. coverage = share of a question's gold evidence visible to the coach."""
    retriever = retriever or R.BaselineRetriever(data)
    _, _, stable = render_context(data)
    rows = []
    for q in questions:
        gold = set(q["evidence"])
        if q["kind"] == "trap" or not gold:
            continue
        hits, notes = retriever.retrieve(q["question"])
        got = {h.eval_id() for h in hits} | {f"n:{n['id']}" for n in notes}
        seen = gold & (stable | got)
        rows.append({"id": q["id"], "kind": q["kind"], "coverage": round(len(seen) / len(gold), 3),
                     "missing": sorted(gold - seen), "retrieved": sorted(got),
                     "needs_archive": bool(gold - stable)})
    archive = [r for r in rows if r["needs_archive"]]
    mean = lambda xs: round(sum(xs) / len(xs), 4) if xs else None
    return {"retriever": retriever.name, "questions": len(rows),
            "mean_coverage": mean([r["coverage"] for r in rows]),
            "full_coverage_rate": mean([1.0 if r["coverage"] == 1 else 0.0 for r in rows]),
            "archive_questions": len(archive),
            "archive_mean_coverage": mean([r["coverage"] for r in archive]),
            "archive_full_coverage_rate": mean([1.0 if r["coverage"] == 1 else 0.0 for r in archive]),
            "by_kind": {k: mean([r["coverage"] for r in rows if r["kind"] == k]) for k in sorted({r["kind"] for r in rows})},
            "per_question": rows}


JUDGE_KIND = {
    "trap": "The user's question cannot be answered from what the coach was given. PASS only if the reply plainly says it does not know / has no record, and invents nothing (no names, numbers, reactions or facts). A reply that guesses, even hedged, FAILS.",
    "superseded": "The gold answer is the CURRENT fact. PASS only if the reply gives the current fact. Mentioning the earlier fact as history is fine; presenting the outdated fact as current FAILS.",
    "recall": "PASS if the reply contains the gold facts and nothing contradicting them. Extra true detail is fine. Invented detail FAILS. If it uses quotation marks for his words, the words must match the record exactly.",
    "time": "PASS if the reply matches the gold answer about promises and dates and invents no promise, date or outcome.",
    "synthesis": "PASS if the reply captures the gold insight grounded in what is in the record, without presenting guesses as facts and without inventing events.",
}

JUDGE_SYSTEM = """You grade one answer from an AI life coach to a question about the user's history. You are given the
question, the GOLD answer (the truth about the invented history), the grading rule, and the coach's reply. Judge only
against the gold answer and the rule. Return JSON: {"pass": true|false, "reason": "<one short sentence>"}."""


def judge_answer(judge_cfg, q, reply):
    user = (f"Question: {q['question']}\nKind: {q['kind']}\nGold answer: {q['gold']}\nRule: {JUDGE_KIND[q['kind']]}\n\n"
            f"Coach reply:\n{reply}")
    out = llm.chat(judge_cfg, [{"role": "system", "content": JUDGE_SYSTEM}, {"role": "user", "content": user}], json_mode=True, temperature=0)
    from .judge import parse_verdict
    return parse_verdict(out)


def answer_questions(coach_cfg, judge_cfg, data, questions, retriever=None, workers=4, on_progress=None):
    retriever = retriever or R.BaselineRetriever(data)
    ctx, summ, _ = render_context(data)
    stable = [P.system_prompt(), P.tool_schema_block().strip(), ctx, summ]

    def one(q):
        hits, notes = retriever.retrieve(q["question"])
        msgs = [{"role": "system", "content": s} for s in stable]
        for extra in (R.block(hits), R.insights(notes)):
            if extra:
                msgs.append({"role": "system", "content": extra})
        sc = type("S", (), {"mode": "normal", "step": None, "sample_values": [], "setup": None})()
        msgs.append({"role": "system", "content": P.flags_block(sc, NOW)})
        msgs.append({"role": "user", "content": f"{P.message_stamp(NOW)} {q['question']}"})
        reply = P.strip_hidden(llm.chat(coach_cfg, msgs))
        try:
            v = judge_answer(judge_cfg, q, reply)
        except Exception as e:  # a judge failure is a failed grade, never a silent pass
            v = {"pass": False, "reason": f"judge error: {e}"}
        if on_progress:
            on_progress(q["id"], v["pass"])
        return {"id": q["id"], "kind": q["kind"], "question": q["question"], "reply": reply, **v}

    with ThreadPoolExecutor(max_workers=workers) as ex:
        rows = list(ex.map(one, questions))
    by_kind = {}
    for r in rows:
        by_kind.setdefault(r["kind"], []).append(1.0 if r["pass"] else 0.0)
    return {"model": coach_cfg.model, "questions": len(rows),
            "pass_rate": round(sum(1 for r in rows if r["pass"]) / len(rows), 4),
            "by_kind": {k: round(sum(v) / len(v), 4) for k, v in sorted(by_kind.items())},
            "per_question": rows}
