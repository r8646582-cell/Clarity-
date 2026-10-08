"""Port of dev/TestBench.kt parsing (prompts/testbench.md). tests/test_testbench.py pins the scenario count."""
import re
from dataclasses import dataclass, field

MODES = {"normal", "listen", "untangle", "decision", "practice", "journey", "onboarding"}


@dataclass
class Scenario:
    name: str
    mode: str
    user_lines: list
    expect: str
    step: str = None
    sample_values: list = field(default_factory=list)
    setup: str = None


def parse_mode(raw: str):
    t = raw.strip().lower()
    head = t.split("(")[0].split(" ")[0].strip()
    mode = head if head in MODES else "normal"
    m = re.search(r"\(\s*([a-z_ ]+?)\s+step\s*\)", t)
    step = m.group(1).strip().replace(" ", "_") if m else None
    v = re.search(r"values?\s*:\s*(.+)$", t)
    values = [x.strip().rstrip(".") for x in v.group(1).split(",") if x.strip().rstrip(".")] if v else []
    return mode, step, values


def parse(markdown: str):
    out, name = [], None
    mode, step, values, setup = "normal", None, [], None
    users, expect, in_expect = [], "", False

    def flush():
        if name is not None and users:
            out.append(Scenario(name, mode, list(users), expect.strip(), step, values, setup))

    for raw in markdown.splitlines():
        line = raw.strip()
        low = line.lower()
        if line.startswith("## "):
            flush()
            name = line[3:].strip()
            mode, step, values, setup = "normal", None, [], None
            users, expect, in_expect = [], "", False
        elif name is None:
            continue
        elif low.startswith("mode:"):
            mode, step, values = parse_mode(line.split(":", 1)[1])
            in_expect = False
        elif low.startswith("(test setup:"):
            setup = line.split(":", 1)[1].strip().removesuffix(")").strip()
            in_expect = False
        elif low.startswith("user:"):
            users.append(line.split(":", 1)[1].strip())
            in_expect = False
        elif low.startswith("expect:"):
            expect = line.split(":", 1)[1].strip()
            in_expect = True
        elif in_expect and line:
            expect += " " + line
    flush()
    return out


def questions(reply: str) -> int:
    return sum(1 for c in reply if c in "?؟")
