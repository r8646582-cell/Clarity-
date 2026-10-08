#!/usr/bin/env python3
"""Purpose scoreboard: one command, one number. See tools/eval/README.md.

  python3 tools/eval/score.py                 # everything that can run (model sections need an API key)
  python3 tools/eval/score.py --only honesty,retrieval
  python3 tools/eval/score.py diff A.json B.json
"""
import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from purpose_eval import RESULTS, bench, honesty, llm, memory_exam, results  # noqa: E402
from purpose_eval import retrieval as R  # noqa: E402


def cfg_from(args, prefix=""):
    g = lambda n, d=None: getattr(args, prefix + n, None) or d
    provider = g("provider", args.provider)
    return llm.Config(provider=provider, base_url=g("base_url", args.base_url), model=g("model", args.model),
                      api_key_env=g("api_key_env", args.api_key_env) or "",
                      temperature=0.7 if not prefix else 0.0)


def have_key(cfg):
    if cfg.provider == "fake":
        return True
    try:
        cfg.key()
        return True
    except SystemExit:
        return False


def table(rows, header):
    w = [max(len(str(r[i])) for r in rows + [header]) for i in range(len(header))]
    fmt = "  ".join("{:<%d}" % x for x in w)
    out = [fmt.format(*header), fmt.format(*["-" * x for x in w])]
    return "\n".join(out + [fmt.format(*[str(c) for c in r]) for r in rows])


def cmd_diff(a, b):
    old, new = json.loads(Path(a).read_text()), json.loads(Path(b).read_text())
    print(f"{a} -> {b}\n" + results.diff(old, new))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="*", help="omit to run; or: diff OLD.json NEW.json")
    ap.add_argument("--only", default="bench,answers,retrieval,honesty", help="comma list of: bench, answers, retrieval, honesty")
    ap.add_argument("--provider", default="openai", choices=["openai", "anthropic", "fake"])
    ap.add_argument("--base-url", default="https://api.deepseek.com")
    ap.add_argument("--model", default="deepseek-flash", help="the coach model under test")
    ap.add_argument("--api-key-env", default="", help="env var holding the key (default PURPOSE_EVAL_API_KEY, then provider's usual)")
    ap.add_argument("--judge-provider", default=None, choices=["openai", "anthropic", "fake"])
    ap.add_argument("--judge-base-url", default=None)
    ap.add_argument("--judge-model", default=None, help="default: same as --model (a stronger judge is better)")
    ap.add_argument("--judge-api-key-env", default=None)
    ap.add_argument("--backup", default=None, help="honesty checks on this Export-my-life/backup JSON (default: the synthetic fixture)")
    ap.add_argument("--limit", type=int, default=0, help="only the first N scenarios / questions (smoke test)")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--retriever", default="baseline", choices=["baseline", "hybrid"],
                    help="hybrid = FTS + on-device MiniLM (needs: pip install onnxruntime numpy; model files from app assets)")
    ap.add_argument("--min-similarity", type=float, default=None, help="hybrid only: cosine cut-off (app default 0.45)")
    ap.add_argument("--no-save", action="store_true")
    ap.add_argument("--out", default=None, help="write the result here instead of eval/results/<date>.json")
    args = ap.parse_args()

    if args.command and args.command[0] == "diff":
        if len(args.command) != 3:
            ap.error("usage: diff OLD.json NEW.json")
        return cmd_diff(args.command[1], args.command[2])

    only = {s.strip() for s in args.only.split(",") if s.strip()}
    coach = cfg_from(args)
    judge = llm.Config(provider=args.judge_provider or coach.provider, base_url=args.judge_base_url or coach.base_url,
                       model=args.judge_model or coach.model, api_key_env=args.judge_api_key_env or coach.api_key_env, temperature=0.0)
    if only & {"bench", "answers"} and not (have_key(coach) and have_key(judge)):
        print("NOTE: no API key found, so the model-graded sections (bench, answers) are skipped. Showing the sections computed\n"
              "      in code only. Set PURPOSE_EVAL_API_KEY (and optionally --judge-*), or use --provider fake to test plumbing.\n")
        only -= {"bench", "answers"}

    sections = {}
    history, questions = memory_exam.load()
    if args.limit:
        questions = questions[:args.limit]
    log = lambda s: print(s, file=sys.stderr, flush=True)

    if "bench" in only:
        scenarios = bench.load_scenarios()[: args.limit or None]
        log(f"bench: {len(scenarios)} scenarios on {coach.model}, judged by {judge.model}")
        sections["bench"] = bench.run(coach, judge, scenarios, args.workers,
                                      lambda r: log(f"  {'PASS' if r['pass'] else 'FAIL'}  {r['scenario']}"))
    retriever_for_answers = None
    if args.retriever == "hybrid" and only & {"retrieval", "answers"}:
        retriever_for_answers = R.HybridRetriever(history, min_similarity=args.min_similarity)
    if "retrieval" in only:
        retriever = retriever_for_answers
        sections["retrieval"] = memory_exam.retrieval_score(history, questions, retriever)
    if "answers" in only:
        log(f"memory answers: {len(questions)} questions")
        sections["answers"] = memory_exam.answer_questions(coach, judge, history, questions, retriever=retriever_for_answers, workers=args.workers,
                                                           on_progress=lambda i, p: log(f"  {'PASS' if p else 'FAIL'}  {i}"))
    if "honesty" in only:
        data = json.loads(Path(args.backup).read_text(encoding="utf-8")) if args.backup else history
        sections["honesty"] = {"source": args.backup or "fixtures/history.json (synthetic)", **honesty.run(data)}

    config = {"provider": coach.provider, "model": coach.model, "judge_model": judge.model, "temperature": coach.temperature,
              "retriever": (sections.get("retrieval") or {}).get("retriever")}
    res = results.make_result(sections, config)

    # ---- print
    print("PURPOSE SCOREBOARD\n")
    rows = []
    sc = res["scores"]
    if "bench" in sc:
        b = sections["bench"]
        rows.append(["test bench", f"{b['passed']}/{b['total']} scenarios pass", sc["bench"]])
    if "answers" in sc:
        a = sections["answers"]
        rows.append(["memory exam: answers", f"{round(a['pass_rate'] * len(a['per_question']))}/{a['questions']} correct", sc["answers"]])
    if "retrieval" in sc:
        r = sections["retrieval"]
        rows.append(["memory exam: retrieval", f"{r['mean_coverage']:.0%} evidence coverage ({r['archive_mean_coverage']:.0%} on archive-only)", sc["retrieval"]])
    if "honesty" in sc:
        h = sections["honesty"]
        rows.append(["honesty (code-checked)", f"quotes {h['quotes']['verification_rate']}, notes {h['notes']['supported_rate']}, done {h['done_states']['clean_rate']}", sc["honesty"]])
    print(table(rows, ["section", "detail", "score"]))
    print(f"\nOVERALL {sc['overall']} / 100   (covers: {', '.join(sc['overall_covers'])})")
    if set(sc["overall_covers"]) != set(results.WEIGHTS):
        print("PARTIAL: only the sections above ran; compare partial runs only with runs that covered the same sections.")
    if coach.provider == "fake":
        print("WARNING: --provider fake. These numbers test the plumbing only and mean nothing about quality.")

    if "bench" in sections:
        print("\nTest bench")
        print(table([[r["scenario"], "PASS" if r["pass"] else "FAIL", r["reason"][:110]] for r in sections["bench"]["scenarios"]],
                    ["scenario", "result", "reason"]))
    if "answers" in sections:
        fails = [r for r in sections["answers"]["per_question"] if not r["pass"]]
        if fails:
            print("\nMemory answers failing")
            print(table([[r["id"], r["kind"], r["reason"][:100]] for r in fails], ["id", "kind", "reason"]))
    if "retrieval" in sections:
        lows = [r for r in sections["retrieval"]["per_question"] if r["coverage"] < 1]
        print(f"\nRetrieval by kind: {sections['retrieval']['by_kind']}   ({len(lows)} questions missing evidence)")
    if "honesty" in sections:
        h = sections["honesty"]
        print(f"\nHonesty: quotes {h['quotes']['total'] - h['quotes']['unverified']}/{h['quotes']['total']} verified; "
              f"{h['notes']['unsupported']} of {h['notes']['risen']} risen notes without his evidence "
              f"({h['notes']['unknown_provenance']} unknown provenance); {h['done_states']['without_user_event']} of "
              f"{h['done_states']['total']} done states without his event")

    if args.no_save:
        return
    path = results.save(res, Path(args.out) if args.out else None)
    print(f"\nSaved {path.relative_to(path.parents[2]) if path.is_relative_to(path.parents[2]) else path}")
    prev = results.previous(res, exclude=path)
    if prev:
        print(f"\nChange since {prev[0].name} (prompts {prev[1].get('prompts_sha')} -> {res['prompts_sha']}):\n" + results.diff(prev[1], res))
    else:
        print("\nNo earlier comparable run to diff against.")


if __name__ == "__main__":
    main()
