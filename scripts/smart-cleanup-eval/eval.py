"""Runs the Smart cleanup prompt against the exact .litertlm model on a desktop.

Usage: python eval.py MODEL.litertlm system.txt examples.json cases.json
Needs `pip install litert-lm` (Python 3.10-3.13). Keep system.txt and examples.json in
sync with SmartCleanup.kt. "want" is the ideal; the app only accepts outputs that keep
every word (DictationFormatter.keepsWords).
"""
import json, re, sys, time
import litert_lm as lm

MODEL = sys.argv[1]
SYSTEM = open(sys.argv[2]).read().strip()
SHOTS = json.load(open(sys.argv[3]))
CASES = json.load(open(sys.argv[4]))

engine = lm.Engine(MODEL, max_num_tokens=1024)
total = 0.0
for case in CASES:
    messages = [{"role": "system", "content": SYSTEM}]
    for raw, clean in SHOTS:
        messages += [{"role": "user", "content": raw}, {"role": "assistant", "content": clean}]
    t = time.time()
    conv = engine.create_conversation(messages=messages,
        sampler_config=lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0),
        thinking_config=lm.ThinkingConfig(enable_thinking=False), max_output_tokens=200,
        extra_context={"enable_thinking": False})
    out = conv.send_message(case["in"])
    text = out["content"][0]["text"] if isinstance(out, dict) else str(out)
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    dt = time.time() - t
    total += dt
    ok = text == case["want"]
    print(f"{'OK ' if ok else '-- '} {dt:4.1f}s  IN : {case['in']}\n           OUT: {text}\n           WANT: {case['want']}")
print(f"avg {total/len(CASES):.2f}s")
