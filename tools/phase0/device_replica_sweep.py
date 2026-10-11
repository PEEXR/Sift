import sqlite3, json, numpy as np, random
import sys; sys.path.insert(0, "."); from evaluate import train_logreg, predict_logreg
db = sqlite3.connect("data/device/sift_database")
rows = db.execute("SELECT id, displayName, embedding FROM screenshots WHERE embedding IS NOT NULL").fetchall()
ids=[r[0] for r in rows]; names={r[0]:r[1] for r in rows}
X = {r[0]: np.frombuffer(r[2], dtype='<f4') for r in rows}
print("dim", len(next(iter(X.values()))))
m = {"Tweets":"tweets","Chats":"chats","Memes":"memes","Animals":"animals","Study notes and papers":"study"}
man = {}
for sid, cname in db.execute("SELECT sc.screenshotId, c.name FROM screenshot_collections sc JOIN collections c ON c.id=sc.collectionId WHERE sc.source='MANUAL'"):
    man.setdefault(m[cname], set()).add(sid)
ref = {d["file"]: set(d["labels"]) for d in map(json.loads, open("data/labels.jsonl"))}
gem = {d["file"]: set(d["labels"]) for d in map(json.loads, open("data/labels_gemma4.jsonl"))}
allman = set().union(*man.values())
test = [i for i in ids if names[i] in ref and names[i] in gem and i not in allman]
A = np.stack([X[i] for i in test])
def f1(pred, cat):
    tp=sum(p and cat in ref[names[i]] for p,i in zip(pred,test)); fp=sum(p for p in pred)-tp
    fn=sum(cat in ref[names[i]] for i in test)-tp
    return 2*tp/(2*tp+fp+fn) if tp else 0
probs={}
for cat, pos in man.items():
    others = set().union(*(v for k,v in man.items() if k!=cat)) - pos
    rng = random.Random(1); cand = sorted(set(ids)-pos); bg = rng.sample(cand, 50)
    neg = sorted(others|set(bg))
    Xt = np.stack([X[i] for i in sorted(pos)]+[X[i] for i in neg]); y=[1]*len(pos)+[0]*len(neg)
    th = train_logreg(Xt, np.array(y, dtype=float), lam=1.0)
    probs[cat]=predict_logreg(A, th)
print("thr  " + " ".join(f"{c:>7}" for c in man) + "   macro")
for t in [0.5,0.4,0.35,0.3,0.25,0.2,0.15]:
    fs=[f1(probs[c]>=t,c) for c in man]
    print(f"{t:.2f} " + " ".join(f"{x:7.2f}" for x in fs) + f"   {np.mean(fs):.3f}")
