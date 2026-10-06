# ADR-0027 — Dispositions JSON et parseur strict écrit à la main

- **Statut** : accepté (phase 6).

## Décision
- Les rangées de lettres viennent de `assets/layouts/*.json` : AZERTY (par défaut), BÉPO, QWERTY, QWERTZ et Dvorak. Le reste du clavier (Maj, Effacer, rangée du bas, symboles) est commun.
- **Schéma** : `{ "format": 1, "id", "name", "rows": [3 rangées] }`.
  - Tous les champs sont obligatoires, aucun autre n'est accepté, pas de doublon.
  - 4 Kio au plus, profondeur 3.
  - Rangées de 1 à 11, 1 à 11 et 1 à 9 touches.
  - Chaque touche est une seule lettre minuscule, ou l'un des caractères `' - ;`, sans doublon.
- **Parseur `LayoutParser`** : écrit à la main, il ne lit que des objets, des tableaux, des chaînes et des entiers. Il refuse les nombres décimaux, les littéraux et les échappements autres que `\" \\ \/ \uXXXX`.
  - Le prompt suggérait `org.json`, mais `org.json` n'est pas exécutable dans les tests JVM (bouchons d'`android.jar`), et un lecteur restreint est plus facile à borner.
- **Échec fermé** : un fichier livré illisible donne l'AZERTY intégré.
- **Aucune disposition n'est chargée depuis l'extérieur.**

## Fuzzing
`LayoutParserTest.mutationsNeverEscapeTheParser` applique 30 000 mutations déterministes aux fichiers livrés. Chaque document muté doit être :
- soit refusé par `LayoutError` ;
- soit une disposition valide qui se construit sans chevauchement.

Jazzer n'est pas ajouté : ce serait une dépendance de test de plus pour un format de 4 Kio sans code natif. Le test vérifie aussi que chaque disposition tient dans le cadre, sans chevauchement, avec ou sans rangée de chiffres.
