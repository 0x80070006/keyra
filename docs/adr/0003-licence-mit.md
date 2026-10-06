# ADR-0003 — Licence MIT et réutilisation de code

- **Statut** : accepté (phase 0)
- **Arbitrage** : D3

## Décision
- Keyra reste sous **licence MIT**.
- **AOSP LatinIME** et **AnySoftKeyboard** (Apache 2.0) : leur code peut être réutilisé. On conserve l'en-tête d'origine, on ajoute une entrée dans `assets/licenses/ATTRIBUTIONS.txt` et on crée un fichier `NOTICE`.
- **FUTO Keyboard** (FUTO Source First License) : on peut reprendre ses idées et ses comportements, **jamais son code**.
- **SwiftKey** (propriétaire) : comportements observables seulement.

## Conséquences
Toute contribution qui s'inspire de près d'un de ces projets doit déclarer son origine dans le message de commit.
