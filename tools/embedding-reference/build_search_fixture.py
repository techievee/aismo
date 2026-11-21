import json, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer

messages = [
 # id, text
 (1,  "Your AGL electricity bill of $142.30 is due on 5 Dec. Pay in the app or at agl.com.au"),
 (2,  "Origin Energy: your gas account is overdue by $88.10. Please pay by Friday to avoid disconnection."),
 (3,  "Telstra: your internet plan renews on 14 Dec for $89. No action needed."),
 (4,  "CommBank: $54.20 spent at WOOLWORTHS BONDI on card ending 4421. Avail bal $1,203.55"),
 (5,  "NAB: a payment of $2,400.00 to LANDLORD PTY LTD was made from your account on 1 Nov."),
 (6,  "Salary credited: $3,850.00 from BETMAKERS PTY LTD to a/c XX9021. Balance $4,102.10"),
 (7,  "123456 is your Uber verification code. Do not share it with anyone."),
 (8,  "Your PayPal security code is 884112. It expires in 10 minutes."),
 (9,  "Qantas: your flight QF410 SYD to MEL departs 7:45am Tuesday. Check in opens 24h before."),
 (10, "Virgin Australia: boarding for VA823 to Brisbane starts 6:10pm at gate 22."),
 (11, "Your Uber Eats order from Dumpling House is on its way. Arriving in 12 minutes."),
 (12, "Australia Post: your parcel AP772193 has been delivered and left at the front door."),
 (13, "StarTrack: we missed you. Your parcel will be redelivered tomorrow between 9am and 5pm."),
 (14, "Reminder: dentist appointment with Dr Chen on Thursday 21 Nov at 2:30pm. Reply C to confirm."),
 (15, "Your prescription is ready for collection at Chemist Warehouse Bondi Junction."),
 (16, "Hi Mum, running late. Can you pick up the kids at 3:30? xx"),
 (17, "Dinner at 7? Table is booked at the Italian place on Oxford St."),
 (18, "Happy birthday mate! Hope you have an awesome day, drinks on Saturday?"),
 (19, "Can you send me the address for Sunday's barbecue? Bringing salad and drinks."),
 (20, "Congratulations! You've won a $1000 gift card. Click http://bit.ly/claim-now to claim your prize."),
 (21, "URGENT: your Medicare account has been suspended. Verify your details at medicare-secure.top"),
 (22, "ATO: you have a tax refund of $1,240 pending. Confirm your bank details at ato-refund.win"),
 (23, "50% OFF everything this weekend only at Rebel Sport. Shop now, ends Sunday."),
 (24, "Dan Murphy's: free delivery on orders over $100 this week. Stock up for summer."),
 (25, "Service NSW: your driver licence renewal is due in 30 days. Renew online to avoid a late fee."),
 (26, "Your Opal card balance is low ($4.20). Top up to keep travelling."),
 (27, "Hi, this is Sarah from Ray White. Are you free for an inspection of the unit at 11am Saturday?"),
 (28, "Your rent of $620 is due tomorrow. Please transfer to the usual account."),
 (29, "Bupa: your claim for physio of $65.00 has been processed. $48.00 will be paid to your account."),
 (30, "University of Sydney: your enrolment for semester 1 opens on 20 January. Log in to choose units."),
 (31, "Netflix: your payment of $22.99 was successful. Thanks for being a member."),
 (32, "Spotify: your Premium plan will renew on 3 Dec at $13.99."),
 (33, "Your car service is booked for Monday 9am at Kia Alexandria. Call us to reschedule."),
 (34, "Hey, the footy is on tonight at the pub, you in?"),
 (35, "Coles: your online order for Wednesday 4pm has been confirmed. 24 items."),
 (36, "Interest of $3.12 was credited to your savings account this month."),
 (37, "Heavy rain warning for Sydney tonight. Avoid unnecessary travel. SES emergency: 132 500."),
 (38, "Your Optus mobile data is 90% used. Add a data pack to stay connected."),
 (39, "Thanks for your payment of $142.30. Your AGL account is now up to date."),
 (40, "Job alert: Senior Android Developer at Canva, Sydney. 5 new matches for you."),
]
# query, ids that are clearly relevant (an unambiguous human judgement)
queries = [
 ("power bill",            [1, 39]),
 ("electricity",           [1, 39]),
 ("flight",                [9, 10]),
 ("verification code",     [7, 8]),
 ("parcel delivery",       [12, 13]),
 ("doctor appointment",    [14, 15, 29]),
 ("scam",                  [20, 21, 22]),
 ("rent",                  [5, 28]),
 ("subscription renewal",  [3, 31, 32]),
 ("birthday",              [18]),
 ("groceries",             [4, 35]),
 ("sale discount",         [23, 24]),
 ("tax refund",            [22]),
 ("job opportunity",       [40]),
]
nomatch = ["quantum physics lecture", "dinosaur fossil museum", "knitting patterns for beginners", "volcano eruption in Iceland"]

tok = Tokenizer.from_file("tokenizer.json"); tok.enable_truncation(max_length=128)
s = ort.InferenceSession("onnx/model_quantized.onnx", providers=["CPUExecutionProvider"])
def emb(t):
    e = tok.encode(t); ids = np.array([e.ids], dtype=np.int64); m = np.array([e.attention_mask], dtype=np.int64)
    h = s.run(None, {"input_ids": ids, "attention_mask": m, "token_type_ids": np.zeros_like(ids)})[0][0, 0]
    return h / np.linalg.norm(h)
D = np.array([emb(t) for _, t in messages]); ids = [i for i, _ in messages]
rel_scores, irr_scores = [], []
print(f"{'query':24s} {'rel min':>8s} {'irr max':>8s}  rank-of-relevant   top3")
for q, rel in queries:
    sc = D @ emb(q); order = np.argsort(-sc)
    ranked = [ids[i] for i in order]
    r = [sc[ids.index(i)] for i in rel]; irr = [sc[k] for k, i in enumerate(ids) if i not in rel]
    rel_scores += r; irr_scores += irr
    ranks = [ranked.index(i) + 1 for i in rel]
    print(f"{q:24s} {min(r):8.3f} {max(irr):8.3f}  {ranks}  {[(ranked[k], round(float(sc[order[k]]),2)) for k in range(3)]}")
nm = []
for q in nomatch:
    sc = D @ emb(q); nm.append(float(sc.max())); print(f"NOMATCH {q:36s} best={sc.max():.3f} (msg {ids[int(sc.argmax())]})")
print("relevant scores: min %.3f  p10 %.3f  median %.3f" % (min(rel_scores), np.percentile(rel_scores, 10), np.median(rel_scores)))
print("irrelevant scores: max %.3f  p99 %.3f  p90 %.3f  median %.3f" % (max(irr_scores), np.percentile(irr_scores, 99), np.percentile(irr_scores, 90), np.median(irr_scores)))
print("no-match best scores:", [round(x, 3) for x in nm])
for T in (0.50, 0.55, 0.60, 0.62, 0.65, 0.70):
    tp = sum(x >= T for x in rel_scores); fp = sum(x >= T for x in irr_scores)
    print(f"floor {T:.2f}: relevant kept {tp}/{len(rel_scores)}  irrelevant shown {fp}  no-match queries with any result {sum(x >= T for x in nm)}/{len(nm)}")
json.dump({"messages": [{"id": i, "text": t} for i, t in messages],
           "queries": [{"query": q, "relevant": r} for q, r in queries], "noMatch": nomatch}, open("search-fixture.json", "w"), ensure_ascii=False, indent=1)
