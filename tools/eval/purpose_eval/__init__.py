"""Purpose offline scoreboard (Blueprint V3, Phase 1). Standard library only; never shipped in the APK."""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
PROMPTS = REPO / "app/src/main/assets/prompts"
RESULTS = REPO / "eval/results"
FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
