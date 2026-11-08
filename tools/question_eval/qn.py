import re,os,sys
src=open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../')+('app/src/main/java/com/example/arfas/ArogyaQuestionNormalizer.kt'),encoding='utf-8').read()
def kmap(name):
    blk=re.search(name+r'[^=]*=\s*(?:linkedMapOf|mapOf)\(([\s\S]*?)\n    \)',src).group(1)
    return dict(re.findall(r'"([^"]+)" to "([^"]*)"',blk))
def kset(name):
    blk=re.search(name+r'\s*=\s*setOf\(([\s\S]*?)\n    \)',src).group(1)
    return set(re.findall(r'"([^"]+)"',blk))
SMS=kmap('val SMS'); HING=kmap('val HINGLISH: Map'); VAR=kmap('val VARIANTS'); COMMON=kset('val COMMON'); HF=kset('val HINGLISH_FUNCTION')
PHR=re.findall(r'"([^"]+)" to "([^"]*)"',re.search(r'HINGLISH_PHRASES = listOf\(([\s\S]*?)\n    \)',src).group(1))
vocab={}
for l in open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../')+('app/src/main/assets/guide/vocab_en.txt')):
    if l.startswith('#'): continue
    w,c=l.rstrip('\n').split('\t'); vocab[w]=int(c)
bylen={}
for w in vocab: bylen.setdefault(len(w),[]).append(w)
def skel(w):
    s=w[0]
    for c in w[1:]:
        if c in 'aeiouyh': continue
        if s[-1]!=c: s+=c
    return s
from collections import defaultdict
g=defaultdict(list)
for k in HING:
    if len(k)>=4: g[skel(k)].append(k)
HBS={k:v[0] for k,v in g.items() if len(v)==1}
KN={'1':'q','2':'wq','3':'ew','4':'re','5':'tr','6':'yt','7':'uy','8':'iu','9':'oi','0':'po'}
LA={'0':'o','1':'il','3':'e','4':'a','5':'s','7':'t','8':'b','9':'g'}
NUM=re.compile(r'\d+(?:\.\d+)?(?:mg|ml|kg|gm|g|cm|mm|c|f|st|nd|rd|th|am|pm|x)?$')
def osa(a,b,lim):
    if abs(len(a)-len(b))>lim: return False
    d=[[0]*(len(b)+1) for _ in range(len(a)+1)]
    for i in range(len(a)+1): d[i][0]=i
    for j in range(len(b)+1): d[0][j]=j
    for i in range(1,len(a)+1):
        for j in range(1,len(b)+1):
            c=0 if a[i-1]==b[j-1] else 1
            d[i][j]=min(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+c)
            if i>1 and j>1 and a[i-1]==b[j-2] and a[i-2]==b[j-1]: d[i][j]=min(d[i][j],d[i-2][j-2]+1)
    return d[-1][-1]<=lim
def canon(t):
    if t in HING: return t
    if t in VAR: return VAR[t]
    if len(t)<4 or not re.fullmatch('[a-z]+',t): return None
    if t in vocab or t in COMMON: return None
    sk=skel(t)
    if len(sk)<2: return None
    k=HBS.get(sk)
    return k if k and len(k)>=4 and k[0]==t[0] else None
def closest(t):
    me=2 if len(t)>=8 else 1; best=None; bd=0
    for L in range(len(t)-me,len(t)+me+1):
        if len(t)<=4 and L<len(t): continue
        for w in bylen.get(L,[]):
            if len(t)<=5 and w[0]!=t[0]: continue
            df=vocab[w]
            if df<10 or df<=bd: continue
            if osa(t,w,me): best=w; bd=df
    return best
def digit(t):
    if sum(c.isdigit() for c in t)!=1 or len(t)<3: return None
    i=next(k for k,c in enumerate(t) if c.isdigit())
    cs=list(dict.fromkeys(KN.get(t[i],'')+LA.get(t[i],'')))
    cand=[t[:i]+c+t[i+1:] for c in cs]
    cand=[c for c in cand if c in vocab or c in COMMON]
    return max(cand,key=lambda c:vocab.get(c,1)) if cand else None
def fix(t,hq):
    if not t or NUM.fullmatch(t): return t
    if t in SMS: return SMS[t]
    if t in HF: return t
    c=canon(t)
    if c: return c
    if t in vocab or t in COMMON: return t
    if any(ch.isdigit() for ch in t) and any(ch.isalpha() for ch in t): return digit(t) or t
    if not t.isalpha() or len(t)<3: return t
    if hq and len(t)<=4: return t
    return closest(t) or t
def norm(q):
    toks=[m.lower() for m in re.findall("[A-Za-z0-9]+(?:['’][A-Za-z]+)?",q)]
    hq=sum(1 for t in toks if (t in HF and t not in vocab and t not in COMMON) or canon(t))>=2
    fixes=[]; eng=[]
    def rep(m):
        t=m.group(0).lower(); u=fix(t,hq)
        if u not in HF:
            c=canon(u)
            if c and c in HING and HING[c] not in eng: eng.append(HING[c])
        if u!=t: fixes.append((m.group(0),u)); return u
        return m.group(0)
    out=re.sub("[A-Za-z0-9]+(?:['’][A-Za-z]+)?",rep,q)
    low=' '+out.lower()+' '
    for p,e in PHR:
        if ' '+p+' ' in low and e not in eng: eng.append(e)
    return out,fixes,eng
if __name__=='__main__':
    for q in sys.stdin.read().strip().split('\n'):
        o,f,e=norm(q); print(f"{q!r}\n   -> {o!r} fixes={f} hing={e}")
