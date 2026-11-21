import json, random, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
tok = Tokenizer.from_file("tokenizer.json"); tok.enable_truncation(max_length=128)

curated = [
 "Your electricity bill of $142.30 is due on 5 Dec. Pay via AGL app.",
 "Rs.1,000.00/- debited from a/c XX9999 on 12-Nov-26. Avl bal Rs 4,500.00",
 "123456 is your Uber verification code. Do not share it.",
 "Café déjà vu: naïve résumé at the Zürich Straße",
 "Prices: €5, £7, ¥900, $12.50 + 10% GST",
 "你好，世界 hello 世界 test 東京タワー",
 "Lunch? 😀🍕 see you at 12:30 😂😂",
 "Visit https://bit.ly/3xYz?utm_source=sms&id=42 now!!",
 "don't can't it's — he said “hello” and ‘bye’",
 "tab\tseparated\nnew\r\nline   multiple   spaces",
 "nbsp here and thin space and ideographic　space",
 "ctrl\u0000char\u000bvertical\u000cformfeed\u0085nel zero​width‍joiner﻿bom",
 "a" * 150,
 "word " * 200,
 "Ελληνικά ΣΑΣ Ο Σίσυφος και Привет Мир",
 "مرحبا بالعالم 123 ٤٥٦",
 "नमस्ते दुनिया — आपका OTP 4821 है",
 "สวัสดีชาวโลก",
 "ǅ ǈ ǋ ǲ İstanbul ǰ ẞ ß ﬁ ﬂ ① ² ½ ™",
 "",
 "   ",
 "4500.00 and 2.5kg and 3x2 and a/b and e-mail and x_y",
 "Flight QF410 SYD→MEL departs 07:45 gate B12; boarding 07:10",
 "HURRY!!! 50% OFF!!! Click: www.promo-deal.win/claim?ref=ABC123 T&C apply",
 "Hi Mum, running late. Can you pick up the kids at 3:30? xx",
 "[CLS] [SEP] [PAD] [MASK] [UNK] literal specials",
 "ＦＵＬＬＷＩＤＴＨ ｆｕｌｌｗｉｄｔｈ １２３",
 "mañana, año, señor, Ångström, Ørsted, Łódź, Đà Nẵng",
 "a.b.c...d,,e;;f::g!!h??i--j__k",
 "Your OTP is 8 4 7 1 2 9 (valid for 10 mins)",
]
random.seed(7)
pool = (list("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") * 3 + list(" \t\n.,;:!?'\"()[]{}-_/\\@#$%^&*+=<>|~`") +
        list("éèêëàâäôöùûüçñßøåæœÀÉÖÜÑ") + list("αβγδΣςΩЖЯюд") + list("中文日本語한국어") + list("😀🍕🎉❤️👍") +
        list("€£¥₹₩©®™°±×÷½¼²³") + [chr(c) for c in (0xA0, 0x2003, 0x3000, 0x200B, 0x200D, 0xFEFF, 0x0, 0xB, 0xC, 0x85, 0x301, 0x300, 0x93F, 0x902, 0xFFFD)])
rand = ["".join(random.choice(pool) for _ in range(random.randint(0, 60))) for _ in range(4000)]

def enc(t): return tok.encode(t).ids
json.dump([{"text": t, "ids": enc(t)} for t in curated], open("tokenizer-golden.json", "w"), ensure_ascii=False)
json.dump([{"text": t, "ids": enc(t)} for t in rand], open("tokenizer-random.json", "w"), ensure_ascii=False)
print("curated", len(curated), "random", len(rand), "max len", max(len(enc(t)) for t in curated))

# reference embeddings through the same model file
s = ort.InferenceSession("onnx/model_quantized.onnx", providers=["CPUExecutionProvider"])
def emb(t):
    e = tok.encode(t); ids = np.array([e.ids], dtype=np.int64); m = np.array([e.attention_mask], dtype=np.int64)
    h = s.run(None, {"input_ids": ids, "attention_mask": m, "token_type_ids": np.zeros_like(ids)})[0][0, 0]
    return (h / np.linalg.norm(h)).astype(np.float32)
texts = curated[0:4] + [curated[6], curated[8], curated[22], curated[23], curated[24], curated[13]]
json.dump([{"text": t, "vector": [round(float(x), 6) for x in emb(t)]} for t in texts], open("embedding-golden.json", "w"), ensure_ascii=False)
print("embedding golden:", len(texts))
