# Rapport de la phase 5 — Prédiction et correction locales

> 2026-10-06. Émulateur Pixel 9a. Détails techniques et mesures : ADR-0025.

## 1. Ce qui a été fait

| Élément | Fichier(s) | Inspiration ou constat |
|---------|-----------|------------------------|
| Moteur Rust : trie, distance pondérée AZERTY, complétions, une seule recherche par frappe | `rust/keyra-core/src/predict.rs`, `keyra-jni` | **R6, R7, A6**, LatinIME, FUTO |
| Façade Kotlin, échec fermé | `engine/Predictor.kt` | — |
| Mot suivant : paires apprises (chiffrées, via `LearningGate`) et table embarquée | `engine/NextWords.kt` | **A5**, AnySoftKeyboard, SwiftKey |
| Bandeau façon SwiftKey : « mot tapé » à gauche, correction en gras au centre, suivante à droite ; emoji pour certains mots | `MintInputService.strip`, `keyboard/KeyboardLayout.Strip` | **A3**, SwiftKey |
| Appui long sur une suggestion : « ne plus proposer » (magasin chiffré `blocked`) | `engine/BlockedWords`, `KeyboardView` | SwiftKey |
| Zones de toucher dynamiques branchées sur la probabilité de la lettre suivante (plafond 25 %, coupées en champ sensible) | `Predictor.nextLetters`, `MintKeyboard.setLetterBias` | SwiftKey |
| Suggestion d'emoji tirée des annotations françaises CLDR (emoji le plus spécifique) | `EmojiCatalog.forWord` | SwiftKey |
| `FrenchCorrector` retiré | — | — |

## 2. Mesures (voir ADR-0025)

- **Qualité** :
  - 83,5 % de corrections justes (79,5 % avant) ;
  - 1,5 % de corrections fausses (4 % avant) ;
  - 58,6 % des lettres oubliées corrigées (24 % avant) ;
  - 1,5 % de mots valides sur-corrigés (4,5 % avant).
- **Vitesse (émulateur)** :
  - 0,53 ms au p50 et **1,53 ms au p95** par frappe (8 et 33 ms avant) ;
  - chargement en **117 ms** (environ 10 s avant).
- **Vérification visuelle** : en tapant « vnir », le bandeau affiche « « vnir » » | **venir** | voir, et le mot en cours est souligné.

## 3. Tests

- **Rust** : 25 tests unitaires et par propriétés, plus le test de qualité `tests/quality.rs`, qui échoue si l'on retombe sous la référence de la phase 0. Une nouvelle cible de fuzzing, `dictionary_load`, s'ajoute.
- **JVM** : 43 tests :
  - `InputLogicTest`, qui vérifie aussi que les paires ne sont apprises qu'au sein d'une phrase ;
  - `ArchitectureTest`, qui vérifie que `NextWords.record` n'est appelé que par `LearningGate`.
- **Instrumentés** : 39 tests, dont le moteur sur l'émulateur et le coût par frappe.

## 4. Considérations de sécurité, fichier par fichier

**`predict.rs`**
- Lecture bornée et validée, sans `unsafe`.
- Une ligne de dictionnaire malformée est ignorée, sans erreur.
- Testé par propriétés et fuzzé.

**`keyra-jni`**
- 4 nouvelles fonctions, sans nouveau bloc `unsafe` : elles réutilisent `copy_byte_array` et `new_byte_array`.
- Chaque mot tapé est effacé des deux côtés.

**`NextWords.kt`**
- Les paires de mots révèlent des habitudes d'écriture. Elles sont chiffrées, limitées à 20 000, jamais apprises en navigation privée, dans une application incognito ni pour un secret (`LearningGate.pair` vérifie les deux mots), et effacées par le geste panique.

**`BlockedWords.kt`**
- La liste des mots masqués est chiffrée. Elle est visible et modifiable dans les réglages.

**`MintInputService.updateLetterBias`**
- Les zones dynamiques sont coupées en champ sensible : leur forme trahirait la prédiction.

**`EmojiCatalog.forWord`**
- Pas de suggestion d'emoji en champ sensible ni en incognito (`noHistory`).

## 5. Limites

- **Modèle de toucher personnel** (décalage moyen par touche) : non livré. Il suppose de faire remonter les coordonnées de chaque frappe jusqu'au moteur. À faire après la phase 7 si l'utilisateur le souhaite.
- **Bigrammes embarqués** : pas de corpus de licence compatible vérifié.
