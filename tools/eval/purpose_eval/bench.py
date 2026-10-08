"""Runs the testbench.md scenarios against a model and grades each Expect line with an LLM judge."""
from concurrent.futures import ThreadPoolExecutor

from . import PROMPTS, llm, prompt as P
from .judge import judge_scenario
from .testbench import parse, questions


def load_scenarios():
    return parse((PROMPTS / "testbench.md").read_text(encoding="utf-8"))


def run_scenario(coach_cfg, judge_cfg, sc):
    history, replies, error = [], [], None
    try:
        for line in sc.user_lines:
            history.append(("user", line))
            raw = llm.chat(coach_cfg, P.build(sc, history))
            replies.append(raw.strip())  # hidden action blocks stay visible to the judge, as in the app's bench
            history[-1:] = [("user", line), ("assistant", P.strip_hidden(raw))]
    except Exception as e:
        error = str(e)
    if error:
        verdict = {"pass": False, "reason": f"coach call failed: {error}"}
    else:
        try:
            verdict = judge_scenario(judge_cfg, sc, replies)
        except Exception as e:
            verdict = {"pass": False, "reason": f"judge error: {e}"}
    return {"scenario": sc.name, "mode": sc.mode, "pass": verdict["pass"], "reason": verdict["reason"],
            "replies": replies, "questions": [questions(r) for r in replies]}


def run(coach_cfg, judge_cfg, scenarios, workers=4, on_progress=None):
    def one(sc):
        r = run_scenario(coach_cfg, judge_cfg, sc)
        if on_progress:
            on_progress(r)
        return r
    with ThreadPoolExecutor(max_workers=workers) as ex:
        rows = list(ex.map(one, scenarios))
    passed = sum(1 for r in rows if r["pass"])
    return {"model": coach_cfg.model, "judge_model": judge_cfg.model, "total": len(rows), "passed": passed,
            "pass_rate": round(passed / len(rows), 4) if rows else None, "scenarios": rows}
