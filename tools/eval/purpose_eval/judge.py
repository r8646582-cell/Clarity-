"""LLM judge for test-bench scenarios: grades each Expect line (JSON verdict). Judge errors count as FAIL."""
import json
import re

from . import llm
from .testbench import questions

SYSTEM = """You are a strict, fair grader of an AI life coach. You get a scenario (the user's lines, the coach's replies)
and an Expect line written by the product owner. The replies show hidden action blocks (<<<TOOL_CALL ... >>> or [[...]])
raw on purpose: whether the coach emitted them matters. Decide whether the replies satisfy EVERY requirement in Expect.
Rules: judge only what the replies show; a requirement not met is a FAIL even if the rest is good; do not reward
length or warmth that Expect did not ask for; do not penalise things Expect did not mention. Return only JSON:
{"pass": true|false, "reason": "<one short sentence naming what decided it>"}"""


def parse_verdict(text: str) -> dict:
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError(f"no JSON in judge output: {text[:120]!r}")
    obj = json.loads(m.group(0))
    return {"pass": bool(obj.get("pass")), "reason": str(obj.get("reason", "")).strip()}


def judge_scenario(cfg, sc, replies):
    lines = []
    for i, u in enumerate(sc.user_lines):
        lines.append(f"User: {u}")
        if i < len(replies):
            lines.append(f"Coach: {replies[i]}")
            lines.append(f"(question marks in that reply: {questions(replies[i])})")
    user = (f"Scenario: {sc.name} (mode: {sc.mode})\n" + (f"Test setup: {sc.setup}\n" if sc.setup else "") +
            "\n".join(lines) + f"\n\nExpect: {sc.expect}")
    out = llm.chat(cfg, [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}], json_mode=True, temperature=0)
    return parse_verdict(out)
