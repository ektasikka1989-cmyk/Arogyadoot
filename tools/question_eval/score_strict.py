"""Stricter scoring (test side only): every expected concern must appear in ONE evidence window that is
also about the question's subject (child / newborn / pregnant woman / mother after delivery / adolescent).
The subject is read from the question with a small test-side list (Hindi, Hinglish, English).
usage: score2.py out.json exp.tsv [rw.tsv]
"""
import json, re, sys

SUBJ = {
    "newborn": (r"newborn|new born|neonat|navjat|नवजात|shishu|शिशु|just born|day old|days old|\b\d+ din ka", r"newborn|new-born|neonat|baby|infant|young infant|नवजात|शिशु|kmc|cord|breastfe"),
    "pregnant": (r"pregnan|pregnent|garbh|गर्भ|antenatal|\banc\b|month pregnant|mahine ki pregnant", r"pregnan|antenatal|anc\b|गर्भ|expectant|before delivery|during labour|labor"),
    "mother": (r"after delivery|delivery ke baad|post ?partum|postnatal|प्रसव के बाद|delivery hui|new mother|feeding mother|stitches", r"mother|delivery|postnatal|post ?partum|post-partum|प्रसव|माँ|lactat"),
    "adolescent": (r"adolescen|teen|kishori|किशोर|periods?|mahwari|माहवारी|menstru|\b1[2-9] ?(year|saal)", r"adolescen|girl|boy|किशोर|menstru|period|young people|peer"),
    "child": (r"child|baby|bacch|bachch|बच्च|infant|toddler|kid|beta|beti|\b\d+ ?(month|mahine|saal|year)s? (old|ka|ki)|बेटा|बेटी|\d+ साल|\d+ महीने", r"child|children|baby|infant|बच्च|under.?five|under.?5|year old|months? old|young infant|sick child|kid"),
}
ORDER = ["newborn", "pregnant", "mother", "adolescent", "child"]


def subject(q):
    ql = q.lower()
    for s in ORDER:
        if re.search(SUBJ[s][0], ql):
            return s
    return None


out = json.load(open(sys.argv[1]))
exp = {l.split('\t')[0]: l.rstrip('\n').split('\t')[1] for l in open(sys.argv[2]) if '\t' in l}
ok = 0; bad = []; loose_ok = 0
for r in out:
    e = exp.get(r['q'])
    if e is None:
        continue
    wins = [w.lower() for w in r.get('windowsFull', [])]
    joined = ' '.join(wins)
    loose = all(re.search(g, joined) for g in e.split(';'))
    loose_ok += loose
    s = subject(r['q'])
    sre = SUBJ[s][1] if s else None
    miss = []
    for g in e.split(';'):
        if not any(re.search(g, w) and (sre is None or re.search(sre, w)) for w in wins):
            miss.append(g)
    if miss:
        bad.append((r['q'], s, miss))
    else:
        ok += 1
print(f"{sys.argv[1]} strict {ok}/{len(out)} (loose {loose_ok})")
for b in bad:
    print("  STRICT-MISS", b)
