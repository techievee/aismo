import json, numpy as np, onnxruntime as ort, itertools
from tokenizers import Tokenizer
fx = json.load(open("search-fixture.json"))
tok = Tokenizer.from_file("tokenizer.json"); tok.enable_truncation(max_length=128)
s = ort.InferenceSession("onnx/model_quantized.onnx", providers=["CPUExecutionProvider"])
def emb(t):
    e = tok.encode(t); ids = np.array([e.ids], dtype=np.int64); m = np.array([e.attention_mask], dtype=np.int64)
    h = s.run(None, {"input_ids": ids, "attention_mask": m, "token_type_ids": np.zeros_like(ids)})[0][0, 0]
    return h / np.linalg.norm(h)
ids = [m["id"] for m in fx["messages"]]; D = np.array([emb(m["text"]) for m in fx["messages"]])
variants = {
  "plain":        lambda q: q,
  "bge-prefix":   lambda q: "Represent this sentence for searching relevant passages: " + q,
  "about-prefix": lambda q: "A text message about " + q,
}
def run(name, f):
    Q = [(q["query"], set(q["relevant"])) for q in fx["queries"]]
    sc = {q: D @ emb(f(q)) for q, _ in Q}; nm = {q: D @ emb(f(q)) for q in fx["noMatch"]}
    ranks = []; hit1 = hit3 = 0
    for q, rel in Q:
        order = [ids[i] for i in np.argsort(-sc[q])]; first = min(order.index(r) for r in rel) + 1
        ranks.append(1 / first); hit1 += first == 1; hit3 += first <= 3
    rec5 = np.mean([len(set([ids[i] for i in np.argsort(-sc[q])[:5]]) & rel) / len(rel) for q, rel in Q])
    prec3 = np.mean([len(set([ids[i] for i in np.argsort(-sc[q])[:3]]) & rel) / 3 for q, rel in Q])
    print(f"{name:13s} MRR {np.mean(ranks):.3f}  hit@1 {hit1}/{len(Q)}  hit@3 {hit3}/{len(Q)}  recall@5 {rec5:.3f}  precision@3 {prec3:.3f}")
    best = None
    for floor, delta in itertools.product(np.arange(0.40, 0.72, 0.02), np.arange(0.02, 0.16, 0.02)):
        P = R = 0.0; fpq = 0
        for q, rel in Q:
            v = sc[q]; top = v.max(); sel = {ids[i] for i in range(len(ids)) if v[i] >= max(floor, top - delta)} if top >= floor else set()
            P += (len(sel & rel) / len(sel)) if sel else 0; R += len(sel & rel) / len(rel)
        for q in fx["noMatch"]:
            v = nm[q]; fpq += v.max() >= floor
        P /= len(Q); R /= len(Q)
        f = 2 * P * R / (P + R) if P + R else 0
        if fpq == 0 and (best is None or f > best[0]): best = (f, floor, delta, P, R)
    f, floor, delta, P, R = best
    print(f"{'':13s} best rule with zero no-match results: floor {floor:.2f}, top-delta {delta:.2f} -> precision {P:.2f} recall {R:.2f} F1 {f:.2f}")
for n, f in variants.items(): run(n, f)

# pooled (micro) numbers for the chosen rule, to compare with the phone's
f = variants["plain"]; Q = [(q["query"], set(q["relevant"])) for q in fx["queries"]]
shown = rel_shown = rel_total = rel_found = 0
for q, rel in Q:
    v = D @ emb(f(q)); top = v.max()
    sel = {ids[i] for i in range(len(ids)) if v[i] >= max(0.60, top - 0.10)} if top >= 0.60 else set()
    shown += len(sel); rel_shown += len(sel & rel); rel_total += len(rel); rel_found += len(sel & rel)
print(f"POOLED plain floor 0.60 window 0.10: shown {shown} relevant-shown {rel_shown} precision {rel_shown/shown:.3f} recall {rel_found/rel_total:.3f}")
