"""Score answers.jsonl from run_llm.py. Generic: works for any guide and any model.

For every answer:
  facts     every expected fact (regex alternatives separated by ';') appears in the answer
  numbers   every number in the answer also appears in the guide text it was given (no invented doses)
  support   share of answer sentences whose content words are mostly found in the given guide text
  loop      a phrase repeated 3+ times in a row
  verdict   GOOD = facts and numbers ok, support >= 0.7, no loop; PART = facts ok but another check failed; WRONG = facts missing
Usage: python score.py answers.jsonl [--show q10]
"""
import json, re, sys, collections

STOP = set("the a an and or of to in on for with is are be by at as it its this that these those from into than then when if any all should must can may will not no do does done give given take taken also only such each per one two there their them they he she his her you your who what which how".split())
LABEL = re.compile(r"(?i)^(answer|why it matters|what to do|when to (tell the anm|refer)|common mistakes( to avoid)?|उत्तर|क्यों ज़रूरी|क्या करें|कब रेफर करें|anm को कब बताएँ|आम गलतियाँ)\s*[:：]\s*")
NUM = re.compile(r"\d+(?:[.,]\d+)?")

def words(t):
    return [w for w in re.findall(r"[\wऀ-ॿ]+", t.lower()) if len(w) > 2 and w not in STOP]

def sentences(t):
    out = []
    for raw in re.split(r"(?<=[.!?।])\s+|\n+", t):
        s = LABEL.sub("", raw.strip()).strip()
        s = re.sub(r"\[\d+\]", "", s).strip()
        if len(s) >= 25 and re.search(r"[\wऀ-ॿ]{3,}", s):
            out.append(s)
    return out

def loop(t):
    w = t.split()
    for n in (4, 6, 8):
        for i in range(0, max(0, len(w) - 3 * n)):
            seg = w[i:i + n]
            if seg == w[i + n:i + 2 * n] == w[i + 2 * n:i + 3 * n]:
                return " ".join(seg)
    return ""

def score(row):
    ans = row.get("answer") or ""; ev = row.get("evidence") or ""
    evl = ev.lower(); evw = set(words(ev)); evnums = set(NUM.findall(ev))
    r = {"id": row["id"], "variant": row["variant"], "chars": len(ans), "ms": row.get("ms"), "error": row.get("error", "")}
    if not ans.strip() or "NOT_IN_GUIDE" in ans:
        r.update(verdict="EMPTY", facts_missing=["(no answer)"], support=0, bad_numbers=[], loop="", unsupported=[])
        return r
    al = ans.lower()
    miss = [f for f in row["expected"].split(";") if not re.search(f, al)]
    bad_nums = sorted(n for n in set(NUM.findall(ans)) if n not in evnums and n not in {"1", "2", "3", "4", "5"})
    sup = 0; unsup = []; sents = sentences(ans)
    for s in sents:
        ws = words(s)
        if not ws:
            sup += 1; continue
        share = sum(1 for w in ws if w in evw or any(w.startswith(e[:6]) or e.startswith(w[:6]) for e in evw if len(e) > 5)) / len(ws)
        if share >= 0.6: sup += 1
        else: unsup.append((round(share, 2), s[:110]))
    support = round(sup / len(sents), 2) if sents else 0
    lp = loop(ans)
    verdict = "WRONG" if miss else ("GOOD" if not bad_nums and support >= 0.7 and not lp else "PART")
    r.update(verdict=verdict, facts_missing=miss, bad_numbers=bad_nums, support=support, loop=lp, unsupported=unsup, sentences=len(sents))
    return r

def main():
    f = sys.argv[1]; show = sys.argv[sys.argv.index("--show") + 1] if "--show" in sys.argv else None
    rows = [json.loads(l) for l in open(f, encoding="utf-8") if l.strip()]
    res = [score(r) for r in rows]
    byv = collections.defaultdict(list)
    for r in res: byv[r["variant"]].append(r)
    print(f"{'variant':16} {'n':>3} {'GOOD':>5} {'PART':>5} {'WRONG':>5} {'EMPTY':>5} {'avg support':>11} {'bad nums':>8} {'loops':>5} {'avg chars':>9} {'avg s':>6}")
    for v, rs in byv.items():
        c = collections.Counter(r["verdict"] for r in rs)
        print(f"{v:16} {len(rs):>3} {c['GOOD']:>5} {c['PART']:>5} {c['WRONG']:>5} {c['EMPTY']:>5} {sum(r['support'] for r in rs)/len(rs):>11.2f} {sum(1 for r in rs if r['bad_numbers']):>8} {sum(1 for r in rs if r['loop']):>5} {sum(r['chars'] for r in rs)//len(rs):>9} {sum((r['ms'] or 0) for r in rs)/len(rs)/1000:>6.0f}")
    print()
    ids = sorted(set(r["id"] for r in res)); vs = list(byv)
    print(f"{'id':5} " + " ".join(f"{v:>14}" for v in vs))
    for i in ids:
        cells = []
        for v in vs:
            r = next((x for x in byv[v] if x["id"] == i), None)
            cells.append(f"{(r['verdict'] + ('*' if r and r['bad_numbers'] else '')) if r else '-':>14}")
        print(f"{i:5} " + " ".join(cells))
    print("\n* = a number not found in the given guide text")
    for r in res:
        if r["verdict"] != "GOOD" and (show is None or r["id"] == show):
            print(f"\n== {r['id']} {r['variant']}: {r['verdict']} facts_missing={r['facts_missing']} bad_numbers={r['bad_numbers']} support={r['support']} loop={r['loop'][:40]!r}")
            for sh, s in r["unsupported"][:3]: print(f"     unsupported({sh}): {s}")
            if show:
                print("   ANSWER:", next(x for x in rows if x["id"] == r["id"] and x["variant"] == r["variant"])["answer"][:1500])

if __name__ == "__main__":
    main()
