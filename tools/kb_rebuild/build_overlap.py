"""Rebuild the ASHA guide DB with overlapping chunks (same schema, ids, tables, user_version).

mode "complete": only a sentence cut at a chunk boundary is completed (the end of a cut sentence is
  appended from the next chunk; the start of it is prepended to the next chunk).
mode "full": additionally one neighbouring sentence of context on each side (bounded).
Embeddings are recomputed (E5 "passage: " + content) only for chunks whose text changed.
usage: build_overlap.py SRC.db OUT.db MODE
"""
import sqlite3, sys, re, shutil, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer

A = "/mnt/user-data/uploads/Arogyadoot/app/src/main/assets"
src, out, mode = sys.argv[1], sys.argv[2], sys.argv[3]
PRE_MAX, POST_MAX = 220, 260          # max chars borrowed from each neighbour
END = re.compile(r"[.!?।](?:[\"')\]]*)(?=\s|$)")


def split_head(content, head):
    if head and content.startswith(head + "\n"):
        return head + "\n", content[len(head) + 1:]
    return "", content


def ends_clean(t):
    t = t.rstrip()
    return bool(t) and (t[-1] in ".!?।" or t[-1] in "\"')]" and len(t) > 1 and t[-2] in ".!?।")


def first_sentences(body, limit, need_one):
    """Text from the start of body to the end of its first sentence (<= limit), else ''."""
    m = END.search(body)
    if m and m.end() <= limit:
        return body[:m.end()].strip()
    return ""


def last_partial(body, limit):
    """The unfinished sentence at the end of body (after its last sentence end), if short enough."""
    ms = list(END.finditer(body))
    tail = body[ms[-1].end():] if ms else body
    tail = tail.strip()
    return tail if 0 < len(tail) <= limit else ""


def last_sentence(body, limit):
    ms = list(END.finditer(body.rstrip()))
    if not ms:
        return ""
    stop = ms[-1].end()
    start = ms[-2].end() if len(ms) > 1 else 0
    s = body[start:stop].strip()
    return s if 0 < len(s) <= limit else ""


shutil.copyfile(src, out)
c = sqlite3.connect(out)
rows = c.execute("select id, doc_id, heading_context, content from procurement_chunks order by id").fetchall()
by_doc = {}
for r in rows:
    by_doc.setdefault(r[1], []).append(r)

new = {}
for doc, rs in by_doc.items():
    parts = [split_head(r[3], r[2]) for r in rs]
    for i, r in enumerate(rs):
        head, body = parts[i]
        pre = post = ""
        if i > 0:
            pb = parts[i - 1][1]
            if not ends_clean(pb):
                pre = last_partial(pb, PRE_MAX)          # start of the sentence this chunk continues
            elif mode == "full":
                pre = last_sentence(pb, PRE_MAX)
        if i + 1 < len(rs):
            nb = parts[i + 1][1]
            if not ends_clean(body):
                post = first_sentences(nb, POST_MAX, True)   # end of the sentence cut here
            elif mode == "full":
                post = first_sentences(nb, POST_MAX, False)
        if pre and body.lstrip().startswith(pre):
            pre = ""
        if pre or post:
            b = body.strip()
            if pre:
                b = pre + " " + b
            if post:
                b = b + (" " if not ends_clean(body) else " ") + post
            new[r[0]] = head + b

print("changed", len(new), "of", len(rows))

tk = Tokenizer.from_file(A + "/semantic/tokenizer.json"); tk.enable_truncation(512); tk.no_padding()
so = ort.SessionOptions(); so.intra_op_num_threads = 2
s = ort.InferenceSession(A + "/semantic/model.onnx", so); names = [i.name for i in s.get_inputs()]


def embed(t):
    e = tk.encode("passage: " + t); ids = np.array([e.ids], dtype=np.int64); m = np.array([e.attention_mask], dtype=np.int64)
    feed = {"input_ids": ids, "attention_mask": m}
    if "token_type_ids" in names: feed["token_type_ids"] = np.zeros_like(ids)
    h = s.run(None, feed)[0][0]; v = (h * m[0][:, None]).sum(0) / m[0].sum(); v = v / np.linalg.norm(v)
    return v.astype("<f4").tobytes()


for k, (cid, text) in enumerate(new.items()):
    c.execute("update procurement_chunks set content=?, char_count=?, embedding=? where id=?", (text, len(text), embed(text), cid))
    if k % 500 == 0:
        print(k, flush=True); c.commit()
c.commit()
print("user_version", c.execute("pragma user_version").fetchone())
c.execute("vacuum"); c.close()
print("done")
