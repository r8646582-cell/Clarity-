"""Run: python3 -m unittest discover -s tools/eval/tests   (no network, no key)."""
import copy
import http.server
import json
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from purpose_eval import FIXTURES, PROMPTS, honesty, llm, memory_exam, prompt as P, results, retrieval as R, testbench  # noqa: E402
from purpose_eval.judge import parse_verdict  # noqa: E402


def history():
    return json.loads((FIXTURES / "history.json").read_text(encoding="utf-8"))


class TestBenchParsing(unittest.TestCase):
    def setUp(self):
        self.sc = testbench.parse((PROMPTS / "testbench.md").read_text(encoding="utf-8"))

    def test_all_44_scenarios_parse_with_user_and_expect(self):
        self.assertEqual(len(self.sc), 44)  # Blueprint: "the 44 testbench.md scenarios"
        for s in self.sc:
            self.assertTrue(s.user_lines and s.expect, s.name)
            self.assertIn(s.mode, testbench.MODES)

    def test_mode_setup_and_values(self):
        by = {s.name: s for s in self.sc}
        v = by["Values coverage"]
        self.assertEqual((v.mode, v.step), ("onboarding", "values"))
        self.assertEqual(v.sample_values, ["family", "knowledge", "faith", "freedom", "health"])
        self.assertIn("Monday 5 Oct", by["Time awareness"].setup)
        self.assertEqual(by["Listen only"].mode, "listen")
        self.assertEqual(len(by["Landing an action"].user_lines), 3)


class PromptMirror(unittest.TestCase):
    def test_tool_schema_is_read_from_the_app_source(self):
        b = P.tool_schema_block()
        self.assertTrue(b.startswith("[Grounding contract]"))
        self.assertIn("advance_journey", b)
        self.assertNotIn('"""', b)

    def test_request_order_and_flags(self):
        sc = next(s for s in testbench.parse((PROMPTS / "testbench.md").read_text()) if s.name == "Journey day not done")
        msgs = P.build(sc, [("user", "can we start tomorrow")])
        roles = [m["role"] for m in msgs]
        self.assertEqual(roles[-1], "user")
        self.assertTrue(msgs[0]["content"].startswith(P.load("persona.md").rstrip()[:40]))
        flags = msgs[-2]["content"]
        self.assertTrue(flags.startswith(P.SYSTEM_ANCHOR))
        for needle in ("mode: journey", "journey_name: Break the avoidance loop", "journey_day: 1", "off_the_record: true"):
            self.assertIn(needle, flags)
        self.assertTrue(any("Today's step from journeys.md:\nDay 1 |" in m["content"] for m in msgs))
        self.assertTrue(msgs[-1]["content"].startswith("[Sun 4 Oct, 14:00] can we"))

    def test_now_from_setup_overrides_default(self):
        t = P.now_from_setup("now = Tuesday 6 Oct 2026, 02:19 Asia/Karachi; open promise")
        self.assertEqual((t.day, t.month, t.hour, t.minute), (6, 10, 2, 19))
        self.assertIn("late night; for him it's still Monday night", P.now_sentence(t))

    def test_hidden_blocks_are_stripped_for_history(self):
        raw = 'Sure, I\'ll save it.\n<<<TOOL_CALL\n{"action":"record_promise"}\n>>>\n[[mode: normal]]'
        self.assertEqual(P.strip_hidden(raw), "Sure, I'll save it.")


class RetrievalParity(unittest.TestCase):
    """Cases copied from Update15MemoryTest.kt so this port cannot drift silently."""

    def test_terms_and_query(self):
        ts = R.terms("I'm so stressed about my FAR exam and Abbu keeps asking about it")
        self.assertEqual(R.match_query(ts), "stressed* OR asking* OR keeps* OR exam* OR abbu* OR far*")
        self.assertIsNone(R.match_query(R.terms("ok so")))

    def test_rank_needs_strong_match_and_excludes_current_session(self):
        ts = R.terms("I'm so stressed about my FAR exam and Abbu keeps asking about it")
        hits = [R.Hit("summary", "3", "2024-02-01", "Talked about the FAR exam and how Abbu reacted to the result."),
                R.Hit("quote", "4:9", "2025-05-01", '"Abbu never asks how I am"'),
                R.Hit("summary", "12", "2026-10-01", "This conversation, FAR exam and Abbu.")]
        self.assertEqual([h.ref for h in R.rank(hits, ts, exclude_session=12)], ["3"])
        self.assertIn("2024-02-01, a conversation: Talked about the FAR exam", R.block(R.rank(hits, ts, 12)))
        self.assertIsNone(R.block(R.rank(hits[:2], R.terms("weather today"), None)))

    def test_fts_roundtrip_on_fixture(self):
        hits, _ = R.BaselineRetriever(history()).retrieve("Which company did I interview with at halcyon maps?")
        self.assertIn("s:7", {h.eval_id() for h in hits})


class MemoryExam(unittest.TestCase):
    def test_fixture_is_consistent_and_clearly_fake(self):
        h, qs = memory_exam.load()
        self.assertTrue(h["synthetic"])
        self.assertGreaterEqual(len(qs), 40)
        ids = {f"s:{s['id']}" for s in h["sessions"]} | {f"q:{q['id']}" for q in h["quotes"]} | \
              {f"e:{e['id']}" for e in h["behaviorEvents"]} | {f"n:{n['id']}" for n in h["notes"]} | \
              {f"p:{p['key']}" for p in h["profile"]} | {f"pr:{p['id']}" for p in h["promises"]}
        for q in qs:
            self.assertFalse(set(q["evidence"]) - ids, q["id"])
            self.assertIn(q["kind"], memory_exam.JUDGE_KIND)
        kinds = {q["kind"] for q in qs}
        self.assertEqual(kinds, {"recall", "time", "synthesis", "superseded", "trap"})
        self.assertGreaterEqual(sum(q["kind"] == "trap" for q in qs), 5)

    def test_retrieval_score_is_deterministic_and_discriminating(self):
        h, qs = memory_exam.load()
        a, b = memory_exam.retrieval_score(h, qs), memory_exam.retrieval_score(h, qs)
        self.assertEqual(a["mean_coverage"], b["mean_coverage"])
        self.assertLess(a["archive_mean_coverage"], 1.0)  # there is room to improve in Phase 2
        self.assertGreater(a["mean_coverage"], 0.3)

    def test_old_facts_are_archive_only(self):
        _, _, stable = memory_exam.render_context(history())
        self.assertNotIn("s:2", stable)       # older than the last five summaries
        self.assertNotIn("p:employer_old", stable)  # retired profile line
        self.assertIn("pr:5", stable)


class Honesty(unittest.TestCase):
    def test_clean_fixture_scores_100(self):
        self.assertEqual(honesty.run(history())["score"], 100.0)

    def test_quote_check_port(self):
        his = ["honestly i think i’m just not built for this", "I opened the book and   closed it again"]
        self.assertTrue(honesty.quote_is_his("I'm just not built for this", his))
        self.assertTrue(honesty.quote_is_his('"opened the book and closed it"', his))
        self.assertFalse(honesty.quote_is_his("I don't think I'm cut out for this", his))
        self.assertFalse(honesty.quote_is_his("no", ["no"]))

    def test_catches_paraphrased_quote_unsupported_note_and_free_done(self):
        d = copy.deepcopy(history())
        d["quotes"].append({"id": 99, "sessionId": 2, "text": "i feel completely invisible at work", "createdAt": 1})
        d["notes"].append({"id": 99, "type": "pattern", "text": "You always quit.", "confidence": "confirmed", "status": "active",
                           "timesSeen": 4, "firstSeen": 1, "lastSeen": 2, "sourceSessionIds": "99"})  # session 99 has no words of his
        d["promises"].append({"id": 99, "text": "Run daily", "createdAt": 5, "status": "kept", "sourceSessionId": 1, "resolvedAt": 6})
        d["journeys"][0]["currentDay"] = 5  # days 2-4 "done" but never spoken
        r = honesty.run(d)
        self.assertEqual(r["quotes"]["unverified"], 1)
        self.assertEqual(r["notes"]["unsupported"], 1)
        self.assertEqual(r["done_states"]["without_user_event"], 4)  # promise + 3 journey days
        self.assertLess(r["score"], 100)

    def test_unknown_provenance_is_not_a_violation(self):
        d = copy.deepcopy(history())
        d["notes"].append({"id": 98, "type": "pattern", "text": "Old note.", "confidence": "likely", "status": "active",
                           "timesSeen": 3, "firstSeen": 1, "lastSeen": 2, "sourceSessionIds": ""})
        r = honesty.run(d)
        self.assertEqual((r["notes"]["unsupported"], r["notes"]["unknown_provenance"]), (0, 1))


class JudgeAndResults(unittest.TestCase):
    def test_verdict_parsing(self):
        self.assertEqual(parse_verdict('```json\n{"pass": true, "reason": "ok"}\n```'), {"pass": True, "reason": "ok"})
        with self.assertRaises(ValueError):
            parse_verdict("looks good to me")

    def test_diff_flags_regressions_and_incomparable_overall(self):
        def res(rate, flags, covers=("bench", "honesty")):
            return {"prompts_sha": "a", "fixtures_sha": "f", "git_commit": "c", "config": {"model": "m"},
                    "scores": {"bench": rate, "honesty": 100.0, "overall": 90.0, "overall_covers": list(covers)},
                    "sections": {"bench": {"scenarios": [{"scenario": k, "pass": v} for k, v in flags.items()]}}}
        out = results.diff(res(100, {"A": True, "B": True}), res(50, {"A": True, "B": False}))
        self.assertIn("scenarios now FAILING: B", out)
        self.assertIn("-50.0", out)
        out2 = results.diff(res(100, {"A": True}), res(100, {"A": True}, covers=("bench", "honesty", "retrieval")))
        self.assertIn("not comparable", out2)

    def test_overall_weights_only_available_sections(self):
        self.assertEqual(results.overall({"bench": 100, "honesty": 50}), round((35 * 100 + 20 * 50) / 55, 1))
        self.assertIsNone(results.overall({}))


class _Echo(http.server.BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _Echo.seen.append((self.path, self.headers.get("Authorization"), body))
        out = json.dumps({"choices": [{"message": {"content": '{"pass": true, "reason": "fine"}'}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


class OpenAiClient(unittest.TestCase):
    def test_request_shape_against_a_local_server(self):
        srv = http.server.HTTPServer(("127.0.0.1", 0), _Echo)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        import os
        os.environ["PURPOSE_EVAL_API_KEY"] = "test-key"
        try:
            cfg = llm.Config(provider="openai", base_url=f"http://127.0.0.1:{srv.server_port}/deepseek", model="m")
            out = llm.chat(cfg, [{"role": "system", "content": "s"}, {"role": "user", "content": "u"}], json_mode=True, temperature=0)
        finally:
            srv.shutdown()
            os.environ.pop("PURPOSE_EVAL_API_KEY")
        path, auth, body = _Echo.seen[-1]
        self.assertEqual((path, auth), ("/deepseek/chat/completions", "Bearer test-key"))
        self.assertEqual((body["model"], body["temperature"], body["response_format"]), ("m", 0, {"type": "json_object"}))
        self.assertEqual(body["thinking"], {"type": "disabled"})
        self.assertEqual(json.loads(out)["pass"], True)


if __name__ == "__main__":
    unittest.main()
