r"""ArogyaDoot laptop scorecard: run a file of prompts through the phone's exact model file.

Usage (Windows, Command Prompt):
    pip install --upgrade litert-lm
    python tools\eval\run_llm.py --model models\gemma-3n-E2B-it-int4.litertlm --prompts tools\eval\prompts.jsonl --out tools\eval\answers.jsonl

Each line of prompts.jsonl: {"id": "...", "variant": "...", "system": "...", "user": "...", "max_tokens": 1600, "temperature": 0.0}
Each line of answers.jsonl: the same fields plus "answer", "ms", "error". Written after every prompt, so a stopped run keeps its work.
Nothing here is specific to one guide or one model: any .litertlm model and any prompt file work.
"""
import argparse, json, os, sys, time

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--backend", default="cpu", choices=["cpu", "gpu"])
    ap.add_argument("--max-num-tokens", type=int, default=4096, help="KV cache size; the phone's real capacity is 4096")
    ap.add_argument("--only", default="", help="comma-separated variants to run (default: all)")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    from litert_lm import Engine
    from litert_lm.interfaces import SamplerConfig, CPU, GPU
    backend = GPU() if args.backend == "gpu" else CPU()

    done = set()
    if os.path.exists(args.out):
        for line in open(args.out, encoding="utf-8"):
            try:
                d = json.loads(line); done.add((d["id"], d["variant"]))
            except Exception:
                pass
    prompts = [json.loads(l) for l in open(args.prompts, encoding="utf-8") if l.strip()]
    only = set(x for x in args.only.split(",") if x)
    todo = [p for p in prompts if (p["id"], p["variant"]) not in done and (not only or p["variant"] in only)]
    if args.limit:
        todo = todo[:args.limit]
    print(f"model {args.model}\nprompts {len(prompts)}, already done {len(done)}, to run {len(todo)}", flush=True)
    if not todo:
        return
    t0 = time.time()
    engine = Engine(model_path=args.model, backend=backend, max_num_tokens=args.max_num_tokens)
    print(f"engine ready in {time.time() - t0:.0f}s", flush=True)
    with open(args.out, "a", encoding="utf-8") as out:
        for i, p in enumerate(todo, 1):
            temp = float(p.get("temperature", 0.0))
            # temperature 0 = greedy (what the phone does for guide answers); otherwise gentle sampling with a fixed seed
            sampler = SamplerConfig(top_k=1, temperature=0.0) if temp <= 0 else SamplerConfig(top_k=40, top_p=0.95, temperature=temp, seed=7)
            t = time.time(); answer = ""; err = ""
            try:
                msgs = []
                if p.get("system"):
                    msgs.append({"role": "system", "content": p["system"]})
                conv = engine.create_conversation(messages=msgs, sampler_config=sampler, max_output_tokens=int(p.get("max_tokens", 1600)))
                try:
                    reply = conv.send_message(p["user"])
                    answer = getattr(reply, "content", None) or getattr(reply, "text", None) or str(reply)
                    if isinstance(answer, list):
                        answer = "".join(getattr(c, "text", "") or (c.get("text", "") if isinstance(c, dict) else "") for c in answer)
                finally:
                    try:
                        conv.close()
                    except Exception:
                        pass
            except Exception as e:  # keep going: one failure is one row
                err = f"{type(e).__name__}: {e}"[:300]
            row = dict(p); row.update(answer=answer, ms=int((time.time() - t) * 1000), error=err)
            out.write(json.dumps(row, ensure_ascii=False) + "\n"); out.flush()
            print(f"{i}/{len(todo)} {p['variant']} {p['id']} {row['ms']} ms {len(answer)} chars {err}", flush=True)

if __name__ == "__main__":
    main()
