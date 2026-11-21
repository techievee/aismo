"""Scores the writing assistant's prompts on a GGUF model, using the same prompts.json the app ships.

    python eval_prompts.py MODEL.gguf [--tuning|--heldout|--all] [--threads N]

Needs llama-cpp-python. A rewrite passes when facts survive, who-does-what is kept, it is not an echo, it has no
preamble, and "shorter" is shorter. These are crude checks written for this fixture; they are not the app's guard.
"""
import argparse, json, re, sys, pathlib
from llama_cpp import Llama

DEFAULT_PROMPTS = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/rephrase/prompts.json"
PROMPTS = None
DRAFTS = json.load(open(pathlib.Path(__file__).with_name("drafts.json")))
MIN_SHORTENABLE = DRAFTS["minShortenable"]   # characters; shorter drafts are not scored for "shorter"
def rows(which): return [(r["draft"], r["facts"], r["must"], r["mustNot"]) for r in DRAFTS["drafts"] if which == "all" or r["set"] == which]
MODES = {"polish": "polish", "fix": "fix", "polite": "polite", "friendly": "friendly", "shorter": "shorter"}
PRE = re.compile(r"^\W*(sure|here|certainly|okay|ok|of course|rewritten|message|revised)\b", re.I)
norm = lambda s: re.sub(r"[^a-z0-9 ]", "", s.lower()).strip()

def messages(mode, draft):
    m = PROMPTS["modes"][mode]; msgs = [{"role": "system", "content": PROMPTS["system"]}]
    for ex in m["examples"]:
        msgs += [{"role": "user", "content": f"{m['instruction']}\n\nMessage: {ex['input']}"}, {"role": "assistant", "content": ex["output"]}]
    return msgs + [{"role": "user", "content": f"{m['instruction']}\n\nMessage: {draft}"}]

def clean(t):
    t = re.sub(r"<think>.*?</think>", "", t, flags=re.S).strip().strip('"').strip()
    return t

TEXTSPEAK = ["u", "ur", "pls", "plz", "thx", "cn", "tmrw", "n", "cant", "dont", "wont", "ive", "im", "ill"]
def judge(mode, draft, facts, must, mustnot, out):
    f = all(re.search(x, out, re.I) for x in facts)
    r = all(re.search(x, out, re.I) for x in must) and not any(re.search(x, out, re.I) for x in mustnot)
    if mode == "shorter": r = r and len(out) < len(draft)
    if mode == "polish":   # "fix everything" must also leave a capital, a full stop, and none of the text-speak that was in the draft
        left = [w for w in TEXTSPEAK if re.search(rf"\b{w}\b", draft) and re.search(rf"\b{w}\b", out)]
        r = r and out[:1].isupper() and out.rstrip()[-1:] in ".!?" and not left
    ok = f and r and not (norm(out) == norm(draft) and mode not in ("fix", "polish")) and not (PRE.search(out) or "\n\n" in out)
    return f, r, ok

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("model"); ap.add_argument("--set", choices=["tuning", "heldout", "all"], default="all")
    ap.add_argument("--threads", type=int, default=8); ap.add_argument("--prompts", default=str(DEFAULT_PROMPTS), help="a prompts.json to try instead of the shipped one"); ap.add_argument("--show", type=int, default=0, help="print this many failures per mode")
    a = ap.parse_args()
    global PROMPTS; PROMPTS = json.load(open(a.prompts))
    data = rows(a.set)
    llm = Llama(model_path=a.model, n_ctx=2048, n_threads=a.threads, verbose=False, seed=7)
    s = PROMPTS["sampling"]; table = {m: [0, 0, 0, 0] for m in MODES}; shown = {m: 0 for m in MODES}
    for mode in MODES:
        for draft, facts, must, mustnot in data:
            if mode == "shorter" and len(draft) < MIN_SHORTENABLE: continue   # nothing to cut from a message this short
            out = None
            for temp in (s["temperature"], s["retryTemperature"]):           # the app retries once, hotter, when a check fails
                r = llm.create_chat_completion(messages=messages(mode, draft), temperature=temp, max_tokens=s["maxNewTokens"], seed=7 if temp == s["temperature"] else 11)
                out = clean(r["choices"][0]["message"]["content"])
                f, rl, ok = judge(mode, draft, facts, must, mustnot, out)
                if temp == s["temperature"]: first_ok = ok
                if ok: break
            table[mode][0] += 1; table[mode][1] += first_ok; table[mode][2] += ok
            if not ok and shown[mode] < a.show: shown[mode] += 1; print(f"  FAIL [{mode}] {draft[:50]!r} -> {out[:100]!r}")
    print(f"{'mode':9s} {'n':>3s} {'first try':>10s} {'with one retry':>15s}")
    for mode, (n, one, two, _) in table.items(): print(f"{mode:9s} {n:>3d} {one:>7d}/{n:<2d} {two:>11d}/{n:<2d}")
    tot = sum(v[0] for v in table.values()); print(f"{'all':9s} {tot:>3d} {sum(v[1] for v in table.values()):>7d}/{tot:<2d} {sum(v[2] for v in table.values()):>11d}/{tot:<2d}")
main()
