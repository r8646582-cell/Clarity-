"""Builds the chat request for a scenario exactly the way TestBenchScreen + ChatRequests + ChatPromptBuilder do:
persona+examples, tool schema, empty-memory context block, the scenario's "Test setup" text, mode instructions, then
the volatile tail (runtime flags with `now`) and the turns. Kept as a mirror, so prompt files are read from the
app's assets and the tool schema is read out of AiConfig.kt rather than copied (nothing to drift)."""
import re
import textwrap
from datetime import datetime
from zoneinfo import ZoneInfo

from . import PROMPTS, REPO

ZONE = ZoneInfo("Asia/Karachi")
DEFAULT_NOW = datetime(2026, 10, 4, 14, 0, tzinfo=ZONE)  # fixed, so runs are comparable
SYSTEM_ANCHOR = "[SYSTEM STATE & TEMPORAL ANCHOR]"
DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
MODE_FILE = {m: f"mode_{m}.md" for m in ("onboarding", "journey", "practice", "decision", "untangle")}


def trim_indent(s: str) -> str:
    lines = s.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    return textwrap.dedent("\n".join(lines))


def load(name: str) -> str:
    return (PROMPTS / name).read_text(encoding="utf-8")


def system_prompt() -> str:
    return load("persona.md").rstrip() + "\n\n" + load("examples.md").strip() + "\n"


def tool_schema_block() -> str:
    src = (REPO / "app/src/main/java/com/umair/purpose/ai/AiConfig.kt").read_text(encoding="utf-8")
    m = re.search(r'val block: String = """(.*?)""".trimIndent\(\)', src, re.S)
    if not m:
        raise RuntimeError("ToolSchemas.block not found in AiConfig.kt")
    return trim_indent(m.group(1))


def clock(t: datetime) -> str:
    h = t.hour % 12
    return f"{12 if h == 0 else h}:{t.minute:02d}{'am' if t.hour < 12 else 'pm'}"


def now_sentence(t: datetime) -> str:
    date = f"{DAYS[t.weekday()]} {t.day} {MONTHS[t.month - 1]} {t.year}"
    h = t.hour
    if h <= 4:
        prev = DAYS[(t.weekday() - 1) % 7]
        part = f"late night; for him it's still {prev} night"
    elif h <= 11:
        part = "morning"
    elif h <= 16:
        part = "afternoon"
    elif h <= 21:
        part = "evening"
    else:
        part = "night"
    d = t.date()
    return (f"It's {date}, {clock(t)} ({part}). Time zone: {t.tzinfo.key}. "
            f"Local now: {t.replace(tzinfo=None).isoformat(timespec='minutes')}. "
            f"Today: {d}; tomorrow: {d.fromordinal(d.toordinal() + 1)}. "
            "Use calendar dates for today/tomorrow; tonight before 4am means the night still in progress.")


def message_stamp(t: datetime) -> str:
    return f"[{DAYS[t.weekday()][:3]} {t.day} {MONTHS[t.month - 1][:3]}, {t.hour:02d}:{t.minute:02d}]"


def now_from_setup(setup):
    """'now = Tuesday 6 Oct 2026, 14:00' in a scenario's setup text, else the fixed default."""
    m = re.search(r"now\s*=\s*\w+\s+(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{4}),\s*(\d{1,2}):(\d{2})", setup or "")
    if not m:
        return DEFAULT_NOW
    mon = [x[:3].lower() for x in MONTHS].index(m.group(2).lower())
    return datetime(int(m.group(3)), mon + 1, int(m.group(1)), int(m.group(4)), int(m.group(5)), tzinfo=ZONE)


def journeys():
    """name -> {day: 'Day N | theme | explore | action'} from journeys.md."""
    out, cur = {}, None
    for line in load("journeys.md").splitlines():
        if line.startswith("## "):
            cur = line[3:].strip()
            out[cur] = {}
        elif cur and re.match(r"Day \d+ \|", line):
            out[cur][int(re.match(r"Day (\d+)", line).group(1))] = line.strip()
    return out


def mode_instructions(sc) -> str:
    if sc.mode not in MODE_FILE:
        return None
    base = load(MODE_FILE[sc.mode])
    if sc.mode == "journey":
        name, day = journey_from_setup(sc.setup)
        step = journeys().get(name, {}).get(day)
        return base + (f"\n\nToday's step from journeys.md:\n{step}" if step else "")
    if sc.mode == "onboarding" and sc.step == "values":
        vals = sc.sample_values
        block = ("His values: he hasn't done the values sort in the app yet." if not vals else
                 "His top values from the values sort, in the order he ranked them:\n" +
                 "\n".join(f"{i + 1}. {v}" for i, v in enumerate(vals)))
        return base.rstrip() + "\n\n" + block
    return base


def journey_from_setup(setup):
    names = journeys()
    name = next((n for n in names if setup and n.lower() in setup.lower()), None)
    m = re.search(r"\bday\s+(\d+)", setup or "", re.I)
    return name, int(m.group(1)) if m else 1


def flags_block(sc, now: datetime) -> str:
    out = [SYSTEM_ANCHOR, "Runtime flags:", f"now: {now_sentence(now)}",
           f"listen_only: {'true' if sc.mode == 'listen' else 'false'}", "tough_love_level: balanced"]
    if sc.mode in MODE_FILE:
        out.append(f"mode: {sc.mode}")
    if sc.mode == "onboarding" and sc.step:
        out.append(f"onboarding_step: {sc.step}")
    if sc.mode == "journey":
        name, day = journey_from_setup(sc.setup)
        if name:
            out.append(f"journey_name: {name}")
        out.append(f"journey_day: {day}")
    out.append("off_the_record: true")  # the bench always runs off the record
    return "\n".join(out)


def stable_prefix(sc):
    """Persona, tool schema, empty-memory context (available journeys only), setup text, mode instructions."""
    msgs = [system_prompt(), tool_schema_block().strip()]
    names = "\n".join(f"- {n}" for n in journeys())
    msgs.append("What you know about Umair so far:\n\nAvailable journeys (start only these, by their exact name):\n" + names)
    if sc.setup:
        msgs.append("Test setup (treat these as the real facts for this conversation; a time given here "
                    f"overrides `now`):\n{sc.setup}")
    mi = mode_instructions(sc)
    if mi and mi.strip():
        msgs.append(mi.strip())
    return msgs


def strip_hidden(raw: str) -> str:
    """What the app saves and shows as the visible reply (hidden TOOL_CALL blocks and [[...]] lines removed)."""
    t = re.sub(r"<<<\s*TOOL_CALL\s*(.*?)\s*>>>", "", raw, flags=re.S | re.I)
    t = re.sub(r"\[\[.*?]]", "", t, flags=re.S)
    return re.sub(r"\n{3,}", "\n\n", t).strip()


def build(sc, history, now=None):
    """history: list of (role, content) so far, the last one being the new user line. Returns chat messages."""
    now = now or now_from_setup(sc.setup)
    msgs = [{"role": "system", "content": s} for s in stable_prefix(sc)]
    msgs.append({"role": "system", "content": flags_block(sc, now)})
    for role, content in history:
        text = f"{message_stamp(now)} {content}" if role == "user" else content
        msgs.append({"role": role, "content": text})
    return msgs
