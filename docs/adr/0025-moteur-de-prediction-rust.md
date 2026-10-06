# ADR-0025 — Moteur de prédiction en Rust, trie construit au chargement

- **Statut** : accepté (phase 5). Remplace `FrenchCorrector` (Kotlin).

## Décision
- **Moteur** : `keyra-core::predict`, avec un trie sur la forme repliée (minuscules, sans accents).
- **Distance d'édition pondérée** par la géométrie AZERTY de Keyra :

  | Erreur | Coût |
  |--------|------|
  | Touche voisine | 0,5 |
  | Autre substitution | 1,0 |
  | Lettre oubliée | 0,6 |
  | Apostrophe ou trait d'union oubliés | 0,2 |
  | Lettre doublée ou voisine en trop | 0,5 |
  | Autre lettre en trop | 0,8 |
  | Inversion | 0,7 |
  | Accent différent | 0,1 |

  On y ajoute des complétions (0,12 par lettre ajoutée).
- **Une seule recherche par frappe** (`analyze`) donne les suggestions et la décision de correction.
- **Correction automatique plus stricte que les suggestions** :
  - plafond de coût de 0,5 + 0,6 × tolérance (+ 0,3 pour un mot de 7 lettres ou plus) ;
  - fréquence minimale de 2 500 à la tolérance par défaut, 50 000 pour un mot de 3 lettres ;
  - marge de confiance sur le deuxième candidat ;
  - mot connu remplacé seulement par sa forme accentuée au moins 20 fois plus fréquente (« deja » → « déjà »).
- **Dictionnaire construit en mémoire au chargement**, à partir du texte embarqué : 117 ms sur l'émulateur Pixel 9a.

## Écart par rapport au prompt : pas de dictionnaire binaire mappé
Le prompt prévoyait un format binaire compilé au build puis mappé en mémoire. La construction en Rust à partir du texte prend 117 ms, sous la cible de 150 ms. On évite ainsi :
- une tâche de build supplémentaire ;
- un second format à valider et à fuzzer ;
- un `mmap` (`unsafe`).

Le chargeur texte est borné (4 Mio, 200 000 lignes, 30 caractères par mot) et fuzzé (`dictionary_load`). On réexaminera la question si le dictionnaire grossit, par exemple avec des bigrammes embarqués.

## Prédiction du mot suivant
- Paires de mots **apprises sur l'appareil**, chiffrées (magasin `bigrams`), via `LearningGate`, sans paire entre deux phrases.
- Petite table française embarquée en repli.
- **Pas de bigrammes embarqués** : aucun corpus de licence compatible n'a été vérifié pour l'instant (FrequencyWords ne fournit que des fréquences de mots isolés).

## Mesures

| | `FrenchCorrector` (phase 0) | Moteur Rust (phase 5) |
|---|---|---|
| Corrections justes (200 fautes) | 79,5 % | **83,5 %** |
| Corrections fausses | 4,0 % | **1,5 %** |
| Lettres oubliées corrigées | 24,1 % | **58,6 %** |
| Attendu dans le top 3 | 96,5 % | **98,5 %** |
| Sur-correction de mots valides hors dictionnaire | 4,5 % | **1,5 %** |
| Coût par frappe (émulateur), p50 / p95 | 8,0 / 32,9 ms | **0,53 / 1,53 ms** |
| Chargement du dictionnaire (émulateur) | ≈ 10 000 ms | **117 ms** |

## Considérations de sécurité
- Le mot tapé traverse le JNI en `byte[]`, puis il est effacé des deux côtés.
- Le moteur, en lecture seule après chargement, est protégé par un `RwLock`.
- Aucune donnée apprise ne va dans le moteur Rust : mots, paires et mots masqués restent dans les magasins chiffrés côté Kotlin.
