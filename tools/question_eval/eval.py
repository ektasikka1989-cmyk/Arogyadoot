import re,os,sys,math,sqlite3,collections
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); import qn
A=os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../')+('app/src/main/java/com/example/arfas/')
# --- question type (port of ArogyaQuestionType.kindOf lists) ---
qt=open(A+'ArogyaQuestionType.kt',encoding='utf-8').read()
def lst(name):
    m=re.search(r'val '+name+r' = listOf\(([\s\S]*?)\n    \)',qt); return re.findall(r'"([^"]*)"',m.group(1))
L={k:lst(k) for k in ['URGENT','PROCEDURE','LIST','DANGER_SIGNS','HOW_MANY','ACTION_ASK']}
def kind(query):
    q=' '+qn.norm(query)[0].lower()+' '
    if any(x in q for x in L['DANGER_SIGNS']): return 'LIST'
    if any(x in q for x in L['URGENT']): return 'URGENT'
    if any(x in q for x in L['HOW_MANY']): return 'FACT'
    if any(x in q for x in L['LIST']): return 'LIST'
    if any(x in q for x in L['PROCEDURE']): return 'PROCEDURE'
    if any(x in q for x in L['ACTION_ASK']): return 'URGENT'
    return 'FACT'
# --- Hindi/Hinglish dictionary views (port of ArogyaHindiTerms.views) ---
ht=open(A+'ArogyaHindiTerms.kt',encoding='utf-8').read()
TERMS=re.findall(r'"([^"]+)" to "([^"]+)"',ht[ht.index('private val TERMS'):])
def views(q):
    o,f,eng=qn.norm(q); low=' '+o.lower()+' '; found=[]
    for e in eng:
        for w in e.split():
            if w not in found: found.append(w)
    for k,en in TERMS:
        if k in low:
            for w in en.split():
                if w not in found: found.append(w)
        if len(found)>=12: break
    return ' '.join(found[:12])
# --- BM25 over the guide ---
db=sqlite3.connect(os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../')+('app/src/main/assets/guide/ASHA_Health_Guide_v2.db'))
docs=[(r[0],r[1],(r[2] or '')) for r in db.execute("select chunk_id,source_name,content from procurement_chunks")]
tok=lambda s: re.findall(r'[a-z0-9]+|[\u0900-\u097f]+',s.lower())
dt=[tok(d[2]) for d in docs]; N=len(dt); avg=sum(map(len,dt))/N
df=collections.Counter(); tfs=[]
for t in dt:
    c=collections.Counter(t); tfs.append(c); df.update(c.keys())
STOP=set('a an the is are was of to in on for and or what how should i do my me it be with has have having can this that at by from'.split())
def bm25(query,k=5):
    q=[w for w in tok(query) if w not in STOP]
    sc=[]
    for i,c in enumerate(tfs):
        s=0
        for w in q:
            if w in c:
                idf=math.log(1+(N-df[w]+.5)/(df[w]+.5)); f=c[w]
                s+=idf*f*2.2/(f+1.2*(.25+.75*len(dt[i])/avg))
        if s>0: sc.append((s,i))
    return [i for s,i in sorted(sc,reverse=True)[:k]]
def ok(i,need):
    t=docs[i][2].lower()
    return all(any(alt in t for alt in grp.split('|')) for grp in need)
def hit(ids,need): return any(ok(i,need) for i in ids)
# (question, expected kind or None, required-term groups for a relevant passage)
Q=[
("The baby is having high fever, what should I do?","URGENT",["fever","baby|child|infant|newborn"]),
("What are the danger signs in a newborn?","LIST",["danger","newborn"]),
("bacche ko dast ho raha hai kya karein","URGENT",["diarrhoea|diarrhea|loose","child|baby"]),
("wat shud i do if pregnent lady has bleding","URGENT",["bleeding","pregnan"]),
("ORS kaise banaye","PROCEDURE",["ors"]),
("a baby is havong h9gh fever",None,["fever","baby|child|infant"]),
("wat shud i do if baby has fevr","URGENT",["fever","baby|child"]),
("bby not drinking milk wat to do","URGENT",["feed|breast|milk|suck","baby|newborn|infant"]),
("newbron baby not feeding",None,["feed","newborn|baby"]),
("child hav diarrea since 3 dys",None,["diarrhoea|diarrhea","child"]),
("kid vomitting after feeding",None,["vomit"]),
("breast feeding positon",None,["breast","position|attach"]),
("what is the dose of paracetmol for 2 year child","FACT",["paracetamol"]),
("pregnent woman bp high",None,["pregnan","blood pressure|bp|hypertension"]),
("mother has bleding after delivry",None,["bleeding","delivery|birth"]),
("baby has diahrea and vomting",None,["diarrhoea|diarrhea","vomit"]),
("cn i giv ors to 2 month baby",None,["ors"]),
("how to giv zink to child","PROCEDURE",["zinc"]),
("pregnent lady bleding wat to do","URGENT",["bleeding","pregnan"]),
("baby ko jhatke aa rahe hai",None,["convulsion|fits|seizure"]),
("bacche ko bukhaar hai kya karein","URGENT",["fever","child|baby"]),
("bachhe ko ulty aur dast",None,["vomit","diarrhoea|diarrhea|loose"]),
("khasi aur tez saans",None,["cough","breath"]),
("garbhvati mahila ko khoon ki kami",None,["anaemia|anemia|haemoglobin|hemoglobin","pregnan"]),
("tez bukhar me kya dena chahiye",None,["fever"]),
("navjat shishu ke khatre ke lakshan","LIST",["danger","newborn"]),
("piliya in newborn",None,["jaundice","newborn|baby"]),
("navjat ki naal me pus",None,["cord|umbilic","newborn|baby"]),
("mahila ko chakkar aur kamzori",None,["dizz|weak|anaemia|anemia"]),
("pet dard aur ulti",None,["pain|abdom","vomit"]),
("sardi zukam me kya kare","URGENT",["cold|cough"]),
("ifa goli kab leni hai",None,["ifa|iron"]),
("zinc kitne din dena hai","FACT",["zinc","days"]),
("bp kitna hona chahiye","FACT",["blood pressure|bp"]),
("बच्चे को बुखार है क्या करें","URGENT",["fever","child|baby"]),
("नवजात शिशु के खतरे के लक्षण","LIST",["danger","newborn"]),
("गर्भवती महिला को खून की कमी",None,["anaemia|anemia|haemoglobin","pregnan"]),
("ओआरएस कैसे बनाएं","PROCEDURE",["ors"]),
("danger signs in pregnancy","LIST",["danger","pregnan"]),
("signs of pneumonia in children","LIST",["pneumonia"]),
("baby has convulsions",None,["convulsion"]),
("child with measles",None,["measles"]),
("how much ifa tablet in pregnancy","FACT",["ifa|iron","pregnan"]),
("when to give vitamin a",None,["vitamin a"]),
("kangaroo mother care steps","PROCEDURE",["kangaroo"]),
("baby weight 2.5kg is it low",None,["birth weight|low birth"]),
("temp 38.5 c what to do","URGENT",["fever|temperature"]),
("dog bite what to do","URGENT",["dog|bite|rabies"]),
("low birth weight baby care",None,["low birth weight"]),
("diarrhoea with blood in stool",None,["blood","stool|diarrhoea|dysentery"]),
]
raw_ok=cln_ok=kind_ok=kind_n=0; bad=[]
for q,k,need in Q:
    o,f,eng=qn.norm(q); v=views(q)
    raw=bm25(q); cl=list(dict.fromkeys(bm25(o,3)+(bm25(v,2) if v else [])))[:5]
    r1=hit(raw,need); r2=hit(cl,need); raw_ok+=r1; cln_ok+=r2
    kd=kind(q)
    if k: kind_n+=1; kind_ok+= (kd==k)
    flag='' if r2 and (not k or kd==k) else '  <-- CHECK'
    print(f"{q[:48]:48} | {('fixed: '+o) if f else '':50.50} | kind={kd:9} | raw={'Y' if r1 else '-'} clean={'Y' if r2 else '-'}{flag}")
    if flag: bad.append((q,o,kd,k,[docs[i][2][:90] for i in cl[:2]]))
print(f"\nRelevant passage in results: raw {raw_ok}/{len(Q)}  cleaned {cln_ok}/{len(Q)}   question type correct {kind_ok}/{kind_n}")
for b in bad: print('CHECK',b)
