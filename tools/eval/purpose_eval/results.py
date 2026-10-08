"""Stores a run in eval/results/<date>.json and diffs it against the previous comparable run."""
import hashlib
import json
import subprocess
from datetime import datetime, timezone

from . import FIXTURES, PROMPTS, REPO, RESULTS

WEIGHTS = {"bench": 35, "answers": 25, "retrieval": 20, "honesty": 20}


def _sha(paths):
    h = hashlib.sha256()
    for p in sorted(paths):
        h.update(p.name.encode())
        h.update(p.read_bytes())
    return h.hexdigest()[:12]


def fingerprint():
    def git(*a):
        try:
            return subprocess.run(["git", *a], cwd=REPO, capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception:
            return ""
    return {"git_commit": git("rev-parse", "--short", "HEAD"), "git_dirty": bool(git("status", "--porcelain", "--", "app")),
            "prompts_sha": _sha(PROMPTS.glob("*.md")), "fixtures_sha": _sha(FIXTURES.glob("*.json"))}


def components(sections: dict) -> dict:
    c = {}
    if sections.get("bench") and sections["bench"].get("pass_rate") is not None:
        c["bench"] = round(100 * sections["bench"]["pass_rate"], 1)
    if sections.get("answers"):
        c["answers"] = round(100 * sections["answers"]["pass_rate"], 1)
    if sections.get("retrieval") and sections["retrieval"].get("mean_coverage") is not None:
        c["retrieval"] = round(100 * sections["retrieval"]["mean_coverage"], 1)
    if sections.get("honesty") and sections["honesty"].get("score") is not None:
        c["honesty"] = sections["honesty"]["score"]
    return c


def overall(comp: dict):
    if not comp:
        return None
    w = sum(WEIGHTS[k] for k in comp)
    return round(sum(WEIGHTS[k] * v for k, v in comp.items()) / w, 1)


def make_result(sections, config):
    comp = components(sections)
    return {"schema": 1, "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), **fingerprint(),
            "config": config, "scores": {**comp, "overall": overall(comp), "overall_covers": sorted(comp)},
            "sections": sections}


def save(result, out=None):
    RESULTS.mkdir(parents=True, exist_ok=True)
    if out:
        path = out
    else:
        day = result["created_at"][:10]
        path = RESULTS / f"{day}.json"
        n = 2
        while path.exists():
            path = RESULTS / f"{day}-{n}.json"
            n += 1
    path.write_text(json.dumps(result, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def previous(result, exclude=None):
    """Latest earlier run of the same kind (fake runs only compare with fake runs)."""
    fake = result["config"].get("provider") == "fake"
    best = None
    for p in RESULTS.glob("*.json"):
        if exclude and p.resolve() == exclude.resolve():
            continue
        try:
            r = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        if (r["config"].get("provider") == "fake") != fake or r["created_at"] >= result["created_at"]:
            continue
        if best is None or r["created_at"] > best[1]["created_at"]:
            best = (p, r)
    return best


def diff(old, new):
    lines = []
    for k in ("prompts_sha", "fixtures_sha", "git_commit"):
        if old.get(k) != new.get(k):
            lines.append(f"  {k}: {old.get(k)} -> {new.get(k)}")
    for k in ("model", "judge_model", "provider"):
        if old["config"].get(k) != new["config"].get(k):
            lines.append(f"  config.{k}: {old['config'].get(k)} -> {new['config'].get(k)}")
    lines.append("")
    keys = sorted(set(old["scores"]) | set(new["scores"]) - {"overall", "overall_covers"})
    for k in [k for k in ("bench", "answers", "retrieval", "honesty") if k in old["scores"] or k in new["scores"]]:
        a, b = old["scores"].get(k), new["scores"].get(k)
        if a is None or b is None:
            lines.append(f"  {k:<10} {a if a is not None else '-':>6} -> {b if b is not None else '-':>6}   (not in both runs)")
        else:
            lines.append(f"  {k:<10} {a:>6} -> {b:>6}   {b - a:+.1f}")
    if old["scores"].get("overall_covers") == new["scores"].get("overall_covers"):
        a, b = old["scores"]["overall"], new["scores"]["overall"]
        lines.append(f"  {'OVERALL':<10} {a:>6} -> {b:>6}   {b - a:+.1f}")
    else:
        lines.append("  OVERALL    not comparable: the runs covered different sections "
                     f"({', '.join(old['scores'].get('overall_covers', []))} vs {', '.join(new['scores'].get('overall_covers', []))})")

    def flips(section, listkey, idkey):
        o = {r[idkey]: r["pass"] for r in (old["sections"].get(section) or {}).get(listkey, [])}
        n = {r[idkey]: r["pass"] for r in (new["sections"].get(section) or {}).get(listkey, [])}
        worse = [k for k in n if k in o and o[k] and not n[k]]
        better = [k for k in n if k in o and not o[k] and n[k]]
        return worse, better
    for section, lk, ik, label in (("bench", "scenarios", "scenario", "scenario"), ("answers", "per_question", "id", "question")):
        worse, better = flips(section, lk, ik)
        if worse:
            lines.append(f"  {label}s now FAILING: {', '.join(worse)}")
        if better:
            lines.append(f"  {label}s now passing: {', '.join(better)}")
    rw = {r["id"]: r["coverage"] for r in (old["sections"].get("retrieval") or {}).get("per_question", [])}
    rn = {r["id"]: r["coverage"] for r in (new["sections"].get("retrieval") or {}).get("per_question", [])}
    changed = [f"{k} {rw[k]}->{rn[k]}" for k in rn if k in rw and rw[k] != rn[k]]
    if changed:
        lines.append("  retrieval coverage changed: " + ", ".join(changed))
    return "\n".join(lines)
