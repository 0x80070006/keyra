# ADR-0012 — Validation de la frappe au relâchement par défaut

- **Statut** : accepté et implémenté (phase 2). La mesure comparative sur appareil réel est reportée, à la demande de l'utilisateur.
- **Arbitrage** : D12

## Contexte
Keyra 11 tape le caractère dès l'appui (`ACTION_DOWN`). FUTO, AnySoftKeyboard et SwiftKey le tapent au relâchement, ce qui permet de corriger une frappe en glissant le doigt.

## Décision
- Les retours visuel, haptique et sonore partent **dès l'appui** : c'est ce qui donne la sensation de réactivité.
- Le caractère est validé **au relâchement**, ou dès qu'un second doigt se pose (« phantom up » de LatinIME). Cette seconde règle garde l'ordre des lettres quand on tape à deux pouces.
- L'option « valider à l'appui » reste disponible dans les réglages.

## Conséquences
Le délai entre l'appui et l'envoi du caractère s'allonge de la durée du contact, environ 50 à 100 ms. Le délai entre l'appui et l'affichage de la touche ne change pas. `PERF.md` doit publier les deux mesures.
