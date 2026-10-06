"""Génère les jeux de qualité du correcteur (données synthétiques, graine fixe).

Sorties :
  app/src/test/resources/fr_typos.tsv      fautes de frappe -> mot attendu
  app/src/test/resources/fr_oov_valid.tsv  mots valides absents du dictionnaire,
                                           qui ne doivent pas être corrigés

Usage : python tools/generate_typos.py
Le résultat est déterministe : relancer le script doit donner des fichiers identiques.
"""
import random
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DICT = ROOT / "app/src/main/assets/fr_frequency.txt"
OUT = ROOT / "app/src/test/resources"
SEED = 20261006

# Géométrie réelle de Keyra 11 (repère 684, MintKeyboard.buildKeys).
XS = [8, 76, 143, 211, 279, 347, 414, 482, 550, 618]
ROWS = ["azertyuiop", "qsdfghjklm", "wxcvbn"]
ROW3_X = [110, 177, 245, 312, 380, 448]
CENTERS = {}
for r, letters in enumerate(ROWS):
    for i, ch in enumerate(letters):
        x = (ROW3_X[i] if r == 2 else XS[i]) + 29
        CENTERS[ch] = (x / 68.0, (95 + 105.5 * r + 42) / 105.5)
NEIGHBORS = {
    a: sorted(b for b in CENTERS if b != a and
              ((CENTERS[a][0] - CENTERS[b][0]) ** 2 + (CENTERS[a][1] - CENTERS[b][1]) ** 2) ** .5 <= 1.5)
    for a in CENTERS
}
ACCENTS = {"é": "e", "è": "e", "ê": "e", "ë": "e", "à": "a", "â": "a", "ù": "u", "û": "u",
           "ô": "o", "î": "i", "ï": "i", "ç": "c"}
ALPHABET = "abcdefghijklmnopqrstuvwxyz"


def fold(s):
    s = s.lower().replace("’", "'").replace("œ", "oe")
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def load():
    words = []
    for line in DICT.read_text(encoding="utf-8").splitlines():
        parts = line.strip().split(" ")
        if len(parts) == 2 and parts[1].isdigit():
            words.append((parts[0].lower(), int(parts[1])))
    return words


def edits1(w):
    splits = [(w[:i], w[i:]) for i in range(len(w) + 1)]
    out = set()
    for a, b in splits:
        if b:
            out.add(a + b[1:])
            for c in ALPHABET:
                out.add(a + c + b[1:])
        if len(b) > 1:
            out.add(a + b[1] + b[0] + b[2:])
        for c in ALPHABET:
            out.add(a + c + b)
    return out


def make_typo(word, kind, rng):
    w = word
    positions = [i for i, c in enumerate(w) if c in NEIGHBORS]
    if kind == "voisine" and positions:
        i = rng.choice(positions[1:] or positions)
        return w[:i] + rng.choice(NEIGHBORS[w[i]]) + w[i + 1:]
    if kind == "omission" and len(w) >= 5:
        i = rng.randrange(1, len(w))
        return w[:i] + w[i + 1:]
    if kind == "inversion":
        i = rng.randrange(1, len(w) - 1)
        if w[i] != w[i + 1]:
            return w[:i] + w[i + 1] + w[i] + w[i + 2:]
    if kind == "ajout" and positions:
        i = rng.choice(positions)
        extra = rng.choice(NEIGHBORS[w[i]] + [w[i]])
        return w[:i + 1] + extra + w[i + 1:]
    if kind == "accent":
        idx = [i for i, c in enumerate(w) if c in ACCENTS]
        if idx:
            i = rng.choice(idx)
            return w[:i] + ACCENTS[w[i]] + w[i + 1:]
    return None


def main():
    rng = random.Random(SEED)
    words = load()
    exact = {w for w, _ in words}
    best_by_fold = {}
    for w, f in words:
        k = fold(w)
        if f > best_by_fold.get(k, ("", -1))[1]:
            best_by_fold[k] = (w, f)
    # Le corpus vient de sous-titres : on écarte l'anglais fréquent et les mots en w/k,
    # rares en français, pour que les fautes ressemblent à une vraie saisie française.
    english = set("""when what with this that have your just like know yeah okay right well from they there
    about good come sorry baby love please thank thanks think time going want look here mean really need
    some sure tell them then will would could should been were their where which while into only over
    back down make take give play stop wait shit fuck yes damn girl boys man men guys hello hey street island""".split())
    # Prénoms et lieux fréquents dans les sous-titres, écrits en minuscules dans le corpus.
    names = set("""serena emily seth ralph alice stuart boston jennifer hector grant louis elliot ashley virginie
    victor michael john paul david peter james george henry harry charlie frank sam max tom tony nick bob ben joe
    dan sarah anna marie jean pierre jacques london york angeles chicago texas california miami vegas lincoln
    lucy rose mary julia laura nina eva emma leo hugo luc marc kevin brian chris steve scott eric ryan sean
    adam noah josh matt dean carl ray roy lee chen jane amy beth lisa dana gina tina ruby holly molly sally
    betty walter oliver arthur bruce clark lois carter parker hunter mason logan dylan tyler jason justin
    jordan morgan taylor casey jessie riley alex sophie chloe zoe lily grace andy billy danny eddie freddy
    henri jimmy johnny lenny marty ricky sammy teddy timmy tommy willie martin simon thomas nicolas philippe
    michel daniel robert richard edward bernard antoine julien vincent laurent olivier patrick francis
    hélène claire julie camille lucas ethan owen ivan igor boris hans otto fred ted ed al jo
    toby stephen jeremy city daisy berlin marshall lord bonnie anderson carla""".split())
    english |= names
    pool = [(w, f) for rank, (w, f) in enumerate(words)
            if 60 <= rank <= 6000 and 4 <= len(w) <= 10 and all(c.isalpha() for c in w)
            and w not in english and "w" not in w and "k" not in w]
    accented = [p for p in pool if any(c in ACCENTS for c in p[0])]

    def ambiguous(typo, expected, freq):
        for e in edits1(fold(typo)):
            hit = best_by_fold.get(e)
            if hit and hit[0] != expected and hit[1] > freq:
                return True
        return False

    rows, seen = [], set()
    plan = [("standard", 140, True), ("difficile", 60, False)]
    kinds = ["voisine"] * 30 + ["omission"] * 20 + ["inversion"] * 15 + ["ajout"] * 15 + ["accent"] * 20
    for level, count, unambiguous in plan:
        made = 0
        while made < count:
            kind = rng.choice(kinds)
            word, freq = rng.choice(accented if kind == "accent" else pool)
            typo = make_typo(word, kind, rng)
            if not typo or typo == word or typo in exact or typo in seen:
                continue
            if fold(typo) == fold(word) and kind != "accent":
                continue
            if unambiguous and ambiguous(typo, word, freq):
                continue
            seen.add(typo)
            rows.append((typo, word, kind, level))
            made += 1

    OUT.mkdir(parents=True, exist_ok=True)
    header = "# Jeu synthétique généré par tools/generate_typos.py (graine %d). Colonnes : faute, attendu, type, niveau.\n" % SEED
    (OUT / "fr_typos.tsv").write_text(header + "".join("\t".join(r) + "\n" for r in rows), encoding="utf-8", newline="\n")

    oov_candidates = """wifi selfie smartphone appli podcast streaming hashtag texto mdr ptdr keyra grapheneos kotlin
    github linux ubuntu firefox whatsapp instagram tiktok youtube netflix spotify airbnb doctolib leboncoin vinted
    blablacar bitcoin blockchain startup freelance télétravail visio webinaire infolettre courriel pourriel
    hameçonnage cybersécurité chiffrement déverrouiller authentifier réinitialiser paramétrer synchroniser
    géolocalisation covoiturage trottinette végétalien quinoa houmous kombucha matcha brunch ramen bobun
    tartiflette chouquette viennoiserie fromagerie mutuelle covid confinement déconfinement vaccinodrome
    autoentrepreneur cryptomonnaie mégaoctet gigaoctet téraoctet smartwatch bluetooth captcha spammer
    émoticône émoji wikipédia télécharger téléversement mailing newsletter followers influenceuse
    youtubeur youtubeuse instagrammeur tutoriel tuto appli applis réseauter googliser procrastiner
    écoanxiété mansplaining écoresponsable trottinettes covoiturer""".split()
    oov = []
    for w in oov_candidates:
        if w not in exact and fold(w) not in best_by_fold and w not in oov:
            oov.append(w)
    (OUT / "fr_oov_valid.tsv").write_text(
        "# Mots valides absents du dictionnaire embarqué : ne doivent pas être corrigés.\n" + "\n".join(oov) + "\n",
        encoding="utf-8", newline="\n")
    print(f"{len(rows)} fautes, {len(oov)} mots hors vocabulaire")


if __name__ == "__main__":
    main()
