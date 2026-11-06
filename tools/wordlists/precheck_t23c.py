#!/usr/bin/env python3
"""T23c calibration precheck (tools only): mirrors the planned Kotlin
NeighbourModel + WordCorrector over the golden corpus. Calibrates
threshold/brake and fixes mix.tsv expected values from the REAL lists.
Throwaway-grade host tool, no app data produced here."""
import json, math, re, sys

# ---------- neighbour model from layout json ----------
def load_model(layout_id):
    d = json.load(open(f'app/src/main/assets/layouts/{layout_id}.json'))
    positions = {}  # char -> (x, y), y = 1.5 * rowIndex (row pitch)
    for lname in ("base", "shift"):
        for y, row in enumerate(d["layers"][lname]["rows"]):
            x = 0.0
            for k in row:
                if k.get("kind") != "text":
                    x += k.get("width", 1.0); continue
                c = k["value"]; w = k.get("width", 1.0)
                pos = (x + w/2.0, 1.5*y)
                positions.setdefault(c, pos)
                for a in k.get("alternates", []):
                    positions.setdefault(a, pos)
                x += w
    return positions

def cost(pos, a, b):
    if a == b: return 0.0
    pa, pb = pos.get(a), pos.get(b)
    if pa is None or pb is None: return 1.0
    d = math.dist(pa, pb)
    if d < 0.25: return 0.4   # same key (long-press alternates)
    return 0.7 if d < 1.5 else 1.0

# ---------- word lists ----------
import importlib.util
spec = importlib.util.spec_from_file_location('bw', 'tools/build_wordlist.py')
bw = importlib.util.module_from_spec(spec); spec.loader.exec_module(bw)

def load_list(lang):
    path = {'de-de':'tools/wordlists/de_full.txt','en-us':'tools/wordlists/en_full.txt'}[lang]
    top = {'de-de':100000,'en-us':150000}[lang]
    stop = f'tools/wordlists/stop_{lang[:2]}.txt'
    ent = bw.collect(path, bw.load_stoplist(stop))[:top]
    idx = {bw.fold(w): r for r,(w,c) in enumerate(ent)}
    forms = {bw.fold(w): w for w,c in ent}
    return idx, forms

DE_IDX, DE_FORMS = load_list('de-de')
EN_IDX, EN_FORMS = load_list('en-us')
DE_POS = load_model('de-de')
EN_POS = load_model('en-us')
IDX = {'de-de': DE_IDX, 'en-us': EN_IDX}
FORMS = {'de-de': DE_FORMS, 'en-us': EN_FORMS}
UMLAUT = [("ae","ä"),("oe","ö"),("ue","ü"),("ss","ß")]

def gen_ops(w, pos, de_ops):
    out = []  # (cand, cost, tag)
    L = len(w)
    for i in range(L):
        for ch in pos:  # substitution, reachable chars only
            if ch != w[i]:
                out.append((w[:i]+ch+w[i+1:], cost(pos, w[i], ch), 'sub'))
        out.append((w[:i]+w[i+1:], 1.0, ('del', i)))  # deletion
    for i in range(L+1):
        for ch in pos:
            out.append((w[:i]+ch+w[i:], 1.0, 'ins'))  # insertion
    for i in range(L-1):
        if w[i] != w[i+1]:
            out.append((w[:i]+w[i+1]+w[i]+w[i+2:], 0.8*cost(pos, w[i], w[i+1]), 'trp'))  # 0.8x pair cost
    if de_ops:
        for a,b in UMLAUT:
            j = w.find(a)
            while j >= 0:
                out.append((w[:j]+b+w[j+len(a):], 0.8, 'uml')); j = w.find(a, j+1)
    return out

def has_umlaut(s): return any(u in s for u in "äöüß")

def correct(word, layout_id, mode, threshold=1.6, verbose=False):
    pos = DE_POS if layout_id=='de-de' else EN_POS
    if layout_id == 'en-us':
        langs = ['en-us'] if mode=='cur' else ['de-de','en-us']
    else:
        langs = ['de-de'] if mode=='cur' else ['de-de','en-us']
    w = word.lower()
    if len(word) < 3 or not word.isalpha(): return None
    if any(w in IDX[l] for l in langs): return ('inlist',)
    cands = {}  # (word, lang) -> (cost, tag)
    step1 = gen_ops(w, pos, de_ops=('de-de' in langs))
    for c1, cost1, tag1 in step1:
        if cost1 > threshold: continue
        for l in langs:
            if l != 'de-de' and has_umlaut(c1): continue
            if c1 in IDX[l]:
                k = (c1, l)
                if k not in cands or cands[k][0] > cost1: cands[k] = (cost1, tag1)
    best1 = min(v[0] for v in cands.values()) if cands else None
    if best1 is None or best1 > 0.8:  # depth 2 only if no cheap hit
        for c1, cost1, tag1 in step1:
            if cost1 > threshold: continue
            for c2, cost2, tag2 in gen_ops(c1, pos, de_ops=('de-de' in langs)):
                tot = cost1 + cost2
                if tot > threshold: continue
                for l in langs:
                    if l != 'de-de' and has_umlaut(c2): continue
                    if c2 in IDX[l]:
                        k = (c2, l)
                        if k not in cands or cands[k][0] > tot: cands[k] = (tot, tag2)
    if not cands: return None
    merged = {}
    for (cand, l), (cst, tag) in cands.items():
        r = IDX[l][cand]
        if cand not in merged or (cst, r) < merged[cand][:2]:
            merged[cand] = (cst, r, l, tag)
    ranked = sorted(merged.items(), key=lambda kv: (kv[1][0], kv[1][1]))
    if verbose: print("   cands:", [(k, v[0], v[1], v[2], v[3]) for k,v in ranked[:6]])
    c1, (cost1, r1, l1, t1) = ranked[0]
    if len(ranked) > 1:
        c2, (cost2, r2, l2, t2) = ranked[1]
        # R4 §14.6e frequent-runner-up brake: runner-up >=10x more frequent AND
        # near in cost AND from an INNER deletion/insertion (phantom-char reading
        # of the whole word); edge deletions (leading/trailing char -> pronouns
        # like ich/mich/dich/sich) do NOT block adjacent-key fixes
        if (cost2-cost1) < 0.45 and r2 * 10 <= r1 and isinstance(t2, tuple):
            pos2 = t2[1]
            if 0 < pos2 < len(w)-1:
                return ('brake-frequent',)
        # R3 cross-language brake (§14.3)
        if l1 != l2 and (cost2-cost1) < 0.5 and r2 < 3*r1:
            return ('brake-cross',)
        # equal cost -> rank tie-break (suggest best), no brake
    if cost1 > threshold: return None
    form = FORMS[l1][c1]
    out = (form[0].upper()+form[1:]) if word[0].isupper() else form.lower()
    return ('fix', out, cost1, r1, l1)

def probe(label, word, layout, mode, expected):
    r = correct(word, layout, mode)
    if expected is None:
        ok = r is None or (r and r[0]=='inlist')
    else:
        ok = (r and r[0]=='fix' and r[1].lower()==expected.lower())
    print(f"{'OK ' if ok else 'FAIL'} {str(label):26s} {word!r} -> {r if r is None else r[:3]} (exp {expected!r})")
    return r if ok else None

if __name__ == '__main__':
    print("== de lexicon rows (mode=cur):")
    for t, e, c in bw.load_corpus('testdata/typing_goldens/de.tsv'):
        if c != 'lexicon': continue
        tw, ew = bw.relevant_words(t), bw.relevant_words(e)
        d = [(a,b) for a,b in zip(tw,ew) if a!=b]
        if len(d) != 1: continue
        i = [k for k,(a,b) in enumerate(zip(tw,ew)) if a!=b][0]
        orig = re.findall(r'[^\W\d_]+', t)[i]
        probe(f"lex:{d[0][1]}", orig, 'de-de', 'cur', d[0][1])
    print("== en lexicon:")
    for t, e, c in bw.load_corpus('testdata/typing_goldens/en.tsv'):
        if c != 'lexicon': continue
        tw, ew = bw.relevant_words(t), bw.relevant_words(e)
        d = [(a,b) for a,b in zip(tw,ew) if a!=b]
        if len(d) == 1: probe(f"lex:{d[0][1]}", d[0][0], 'en-us', 'cur', d[0][1])
    print("== morph typos:")
    for w, exp, lay in [("gegnagen","gegangen","de-de"),("Hääuser","Häuser","de-de"),
                        ("schneler","schneller","de-de"),("Fruehstueck","Frühstück","de-de"),
                        ("fuer","für","de-de"),("haette","hätte","de-de"),
                        ("koennen","können","de-de"),("waere","wäre","de-de"),
                        ("chidlren","children","en-us"),("gose","goes","en-us"),("bettre","better","en-us")]:
        probe(exp, w, lay, 'cur', exp)
    print("== mix:")
    for w, exp, lay, mode in [("Meeitng","Meeting","de-de","all"),("Jbo","Job","de-de","all"),
        ("Udpate","Update","de-de","all"),("Dwonload","Download","de-de","all"),("Taem","Team","de-de","all"),
        ("Projetk","Projekt","de-de","cur"),("Projetk","Projekt","de-de","all"),
        ("Feedbakc","Feedback","de-de","all"),("Onlien","Online","de-de","all"),
        ("gecancelt",None,"de-de","all"),("Mial",None,"de-de","all"),
        ("Team-Meetnig","Team-Meeting","de-de","all")]:
        if '-' in w:
            print("SKIP-hyphen", w); continue
        probe(exp, w, lay, mode, exp)
    print("== compounds (expect inlist-or-None):")
    for w in ["Wandkalenderhalter","Steuererklärungsfrist","Fahrradschloss","Küchentischlampe","Fahrrad-Schloss","Wandkelenderhalter","Teammeeting"]:
        r = correct(w.replace('-','') if '-' in w else w, 'de-de', 'all')
        print("   ", w, "->", r if r is None else r[:3])
    print("== clean de words (expect None/inlist):")
    for t, e, c in bw.load_corpus('testdata/typing_goldens/de.tsv'):
        if c == 'clean':
            for tok in t.split():
                if '://' in tok or '@' in tok: continue
                for w in re.findall(r'[^\W\d_]+', tok):
                    if len(w) >= 3 and w.isalpha():
                        r = correct(w, 'de-de', 'cur')
                        if r is not None and r[0] != 'inlist': print("   FP:", w, r[:3])
    print("== brake/miss details:")
    for word, lay, mode in [("kalr","de-de","cur"),("jetzy","de-de","cur"),("Gatren","de-de","cur"),
        ("heuet","de-de","cur"),("schient","de-de","cur"),("Huse","de-de","cur"),("gte","de-de","cur"),
        ("trefen","de-de","cur"),("Staton","de-de","cur"),("kaze","de-de","cur"),("Sontag","de-de","cur"),
        ("warrten","de-de","cur"),("Gruse","de-de","cur"),("haette","de-de","cur"),("gose","en-us","cur"),
        ("Mial","de-de","all"),("Teammeeting","de-de","all"),("gecancelt","de-de","all")]:
        print(" --", word)
        correct(word, lay, mode, verbose=True)
    correct("Mial", 'de-de', 'all', verbose=True)
    correct("Bud", 'de-de', 'cur', verbose=True)
