"""Minimal chat clients (stdlib urllib): OpenAI-compatible (DeepSeek etc.), Anthropic, and a deterministic fake used
only to test the plumbing. Keys come from the environment, never from files in the repo."""
import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass
class Config:
    provider: str = "openai"  # openai | anthropic | fake
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-flash"
    api_key_env: str = ""
    temperature: float = 0.7
    max_tokens: int = 2048

    def key(self):
        names = [self.api_key_env] if self.api_key_env else []
        names += ["PURPOSE_EVAL_API_KEY"] + (["ANTHROPIC_API_KEY"] if self.provider == "anthropic" else ["DEEPSEEK_API_KEY", "OPENAI_API_KEY"])
        for n in names:
            if n and os.environ.get(n):
                return os.environ[n]
        raise SystemExit(f"No API key: set one of {', '.join(n for n in names if n)} (or use --provider fake to test plumbing).")


def _post(url, headers, body, retries=4):
    data = json.dumps(body).encode()
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", **headers})
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code in (408, 429, 500, 502, 503, 529) and attempt < retries:
                time.sleep(2 ** (attempt + 1))
                continue
            raise RuntimeError(f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")
        except urllib.error.URLError as e:
            if attempt < retries:
                time.sleep(2 ** (attempt + 1))
                continue
            raise RuntimeError(f"network: {e}")


def chat(cfg: Config, messages, json_mode=False, temperature=None) -> str:
    """messages: [{role, content}], system messages first. Returns the reply text."""
    temp = cfg.temperature if temperature is None else temperature
    if cfg.provider == "fake":
        return fake_reply(messages, json_mode)
    if cfg.provider == "anthropic":
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        turns = [m for m in messages if m["role"] != "system"]
        if json_mode:
            system += "\n\nRespond with a single JSON object and nothing else."
        out = _post(cfg.base_url.rstrip("/") + "/v1/messages",
                    {"x-api-key": cfg.key(), "anthropic-version": "2023-06-01"},
                    {"model": cfg.model, "max_tokens": cfg.max_tokens, "temperature": temp, "system": system, "messages": turns})
        return "".join(b.get("text", "") for b in out["content"])
    body = {"model": cfg.model, "messages": messages, "temperature": temp, "max_tokens": cfg.max_tokens, "stream": False}
    if "deepseek" in cfg.base_url:
        body["thinking"] = {"type": "disabled"}  # chat runs non-thinking, as in the app
    if json_mode:
        body["response_format"] = {"type": "json_object"}
    out = _post(cfg.base_url.rstrip("/") + "/chat/completions", {"Authorization": f"Bearer {cfg.key()}"}, body)
    return out["choices"][0]["message"].get("content") or ""


def fake_reply(messages, json_mode):
    """Deterministic stand-in. NOT a quality signal: it exists so the pipeline and tests run without a key."""
    if json_mode:
        return json.dumps({"pass": True, "reason": "fake judge", "answer": "I don't know."})
    return "I hear you. What feels like the heaviest part of that right now?"
