# Rapport de la phase 4 — Logique de saisie

> 2026-10-06. Émulateur Pixel 9a. Phases enchaînées sans arrêt, à la demande de l'utilisateur.

## 1. Ce qui a été fait

| Élément | Fichier | Constat ou inspiration |
|---------|---------|------------------------|
| Logique de saisie pure, testable sur la JVM | `ime/InputLogic.kt`, `ime/TextTarget.kt` | LatinIME (`InputLogic`) |
| Cache local du texte, positions attendues du curseur, réponses de l'application bornées | `ime/RichInputConnection.kt` | **R3**, menace D-1, LatinIME (`RichInputConnection`) |
| Mot en cours souligné (composition) ; réglage pour le désactiver | `InputLogic`, `ImeSettings.composing` | FUTO, LatinIME |
| Majuscule automatique selon CAP_SENTENCES, CAP_WORDS et CAP_CHARACTERS ; jamais contre une Maj de l'utilisateur | `InputLogic.autoCaps`, `MintKeyboard.setAutoShift` | **A1** |
| Typographie française : espace fine insécable (ou insécable, ou rien) avant ? ! ; :, guillemets « », coupée dans les adresses et mots de passe | `InputLogic.spaceBeforeHighPunctuation` | **A2** |
| Correction synchrone à l'espace, préparée pendant la frappe, attente bornée à 30 ms | `MintInputService.corrections`, `CORRECTION_WAIT_MS` | **R4** |
| Premier mot d'une phrase corrigé (en minuscules, majuscule remise), noms propres en milieu de phrase intacts | `InputLogic.correct` | défaut trouvé par l'essai réel |
| Retour arrière après correction : mot restauré, appris, plus corrigé dans ce champ | `InputLogic.undoLastCorrection` | **A4**, LatinIME |
| Retour arrière qui accélère (350 ms, puis 80 → 25 ms), mot par mot après 1,5 s ; glisser vers la gauche sélectionne des mots | `KeyboardView` | **A8**, Gboard, SwiftKey |
| Pavé tactile sur la barre d'espace | `KeyboardView` | FUTO, SwiftKey |
| Gestes : vers le bas masque, vers le haut Maj, vers la gauche efface un mot (désactivé par défaut) ; seulement si le geste commence sur un caractère | `KeyboardView.gestureFor` | AnySoftKeyboard |
| Double espace : point seulement si les deux espaces sont à moins de 600 ms | `InputLogic` | LatinIME |
| Réglages relus seulement quand ils changent | `ime/ImeSettings.kt` | **R8**, **R11** |
| Délai des suggestions : 85 → 40 ms | `MintInputService` | **R5** (en partie, la suite en phase 5) |

## 2. Vérifications

- **JVM** : 42 tests, dont `InputLogicTest` (17 tests) sur un éditeur simulé :
  - composition puis correction ;
  - annulation et apprentissage du mot restauré ;
  - mots personnels ;
  - typographie (fine, insécable, coupée, adresses, guillemets) ;
  - double espace ;
  - effacement par mot et sélection ;
  - majuscule automatique ;
  - champs mot de passe ;
  - suggestion avec majuscule ;
  - début de phrase.
- **Instrumentés** : 39 tests, dont `GestureTest` :
  - pavé tactile ;
  - glisser depuis Retour arrière ;
  - balayages vers le bas et vers le haut ;
  - un petit glissement reste une correction.
- **Essai réel sur l'émulateur, avec le vrai clavier** : la saisie de « bonjuor ca va? » donne **« Bonjour ca va ? »**, avec majuscule automatique, correction à l'espace et espace fine insécable avant « ? ». Cet essai a révélé deux défauts, corrigés et couverts par des tests :
  1. le premier mot d'une phrase n'était jamais corrigé ;
  2. un cache de correction ignorait la casse.

## 3. Considérations de sécurité, fichier par fichier

**`RichInputConnection.kt`**
- Les réponses de l'application cible sont bornées à 1 000 caractères. Une application hostile qui renverrait davantage est tronquée.
- Le cache contient le texte avant le curseur (1 000 caractères au plus). Il est vidé et relu au changement de champ, jamais écrit sur disque (ADR-0011).

**`InputLogic.kt`**
- Le mot restauré par Retour arrière passe par `LearningGate`. Rien n'est appris en navigation privée, dans un champ sensible, ni si le mot ressemble à un secret.
- La typographie est coupée dans les adresses e-mail, les URL et les mots de passe, pour ne pas altérer un identifiant.

**`MintInputService.kt`**
- Le cache `corrections` (32 entrées au plus) contient des mots tapés. Il reste en mémoire seulement et est vidé à chaque champ.
- La correction attendue au plus 30 ms est calculée sur un fil dédié, sans accès réseau.

**`KeyboardView.kt`**
- Les gestes et le pavé tactile sont coupés en champ sensible (`quiet` pour le pavé tactile). Les gestes ne commencent que sur un caractère.

## 4. Reste pour la phase 5

- Bandeau de suggestions à la SwiftKey (meilleure suggestion au centre, en gras si elle sera appliquée).
- Dictionnaire binaire et correcteur rapide : le chargement prend environ 10 s et le coût d'une frappe environ 33 ms au p95 (émulateur, build debug).
- Prédiction du mot suivant apprise.
- Zones dynamiques branchées sur les probabilités.
