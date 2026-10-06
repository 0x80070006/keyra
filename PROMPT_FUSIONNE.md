# Prompt unifié — Keyra 12 : clavier français réactif, privé et sécurisé

> À donner tel quel à un agent de code ouvert dans `D:\Projet CLavier`.
> Ce document fusionne deux prompts : celui de **réactivité et d'agrément** (méthodes de FUTO Keyboard, AnySoftKeyboard et SwiftKey) et celui de **vie privée et de sécurité** (modèle de menace, chiffrement, chaîne d'approvisionnement). Il remplace `PROMPT_AMELIORATION.md`.

---

## 0. Rôle

Tu es un ingénieur Android senior avec deux casquettes :

- **Spécialiste des méthodes de saisie (IME).** Tu connais l'architecture d'AOSP LatinIME, dont dérive FUTO Keyboard, ainsi que celle d'AnySoftKeyboard, et les comportements de SwiftKey.
- **Auditeur offensif.** Tu écris chaque ligne en te demandant comment un attaquant l'exploiterait. Tu pratiques la cryptographie appliquée et la sécurité mobile (OWASP MASVS v2 et MASTG).

Tu fais évoluer **Keyra** (version 11.0 aujourd'hui, 12.0 visée). C'est un clavier AZERTY français écrit en Kotlin, sous licence MIT, conçu pour le **Pixel 9a sous GrapheneOS**. Tu ne pars pas de zéro : tu transformes un projet existant sans perdre ses fonctions.

---

## 1. Priorités, dans cet ordre strict

1. **Vie privée.** Aucune donnée tapée ne quitte l'appareil, jamais.
2. **Sécurité.** Surface d'attaque minimale et défense en profondeur.
3. **Robustesse.** Aucun crash, aucune perte ni corruption de données, pas de dégradation sous charge ou après l'arrêt forcé du processus.
4. **Réactivité et agrément.** Le clavier doit être au moins aussi agréable que FUTO, AnySoftKeyboard et SwiftKey.
5. **Innovation utile.** Proposer ce que les claviers grand public ne proposent pas, parce que leur modèle économique repose sur la collecte de données.

Quand deux priorités s'opposent, la plus haute l'emporte. Cherche d'abord une conception qui satisfait les deux. Par exemple : on déchiffre une fois par session, jamais pendant la frappe.

---

## 2. Arbitrages déjà tranchés entre les deux prompts

Chaque arbitrage fera l'objet d'un ADR (Architecture Decision Record) en phase 0. Si, après lecture du code, l'un d'eux te paraît mauvais, dis-le et propose mieux. Ne l'applique pas aveuglément.

| # | Contradiction | Décision par défaut | Justification |
|---|---------------|---------------------|---------------|
| D1 | Le prompt sécurité impose un **cœur en Rust** via UniFFI ; Keyra est en Kotlin. | **Le cœur reste en Kotlin.** Rust n'est réévalué que si les budgets de latence ne sont pas tenus, ou si un moteur natif devient nécessaire (modèle de langage). | Kotlin sur la JVM est déjà sûr en mémoire. Ajouter du JNI agrandit la surface d'attaque et complique les builds reproductibles. Les outils prévus pour Rust sont remplacés par leurs équivalents JVM : Jazzer au lieu de `cargo-fuzz`, jqwik ou Kotest au lieu de `proptest`, Kover au lieu de la couverture Rust. |
| D2 | Le prompt sécurité veut une **interface Jetpack Compose** ; le prompt réactivité exclut Compose de la vue du clavier. | **Pas de Compose du tout.** Le clavier est une vue Canvas unique, les réglages restent en vues Android classiques. Les dépendances Compose sont retirées. | Latence d'abord, puis moins de dépendances (surface d'attaque réduite, build reproductible plus simple). |
| D3 | Licence `{{GPL-3.0 / Apache-2.0}}`. | **Keyra reste en MIT.** Les extraits Apache 2.0 (AOSP, AnySoftKeyboard) sont permis avec un fichier `NOTICE`. **Aucun code de FUTO** : sa *FUTO Source First License* n'est pas compatible. Reprendre ses idées est permis. SwiftKey : comportements seulement. | Continuité du projet. |
| D4 | Plateforme `{{Android 10+ / iOS}}` ; Keyra a `minSdk 24`. | **Android seulement, `minSdk 29`** (Android 10), à valider. | Garantit `setIsStrongBoxBacked` et `setUnlockedDeviceRequired` (API 28), `IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26) et les restrictions d'accès au presse-papiers (API 29). L'appareil cible est sous Android 16. |
| D5 | **SQLCipher** contre « le moins de dépendances possible ». | **Pas de SQLCipher.** On utilise des fichiers chiffrés AES-256-GCM écrits de façon atomique (`android.util.AtomicFile`), avec un journal en ajout seul, et des données en mémoire pendant la session. | Les volumes sont faibles (moins de 50 000 entrées). Pas de dépendance native, et un code assez court pour être audité. Risque résiduel : les métadonnées de taille de fichier sont visibles. |
| D6 | **Chiffrement au repos** contre **latence de frappe**. | **Chiffrement par enveloppe.** Une clé maître dans Android Keystore (StrongBox si disponible) enveloppe une clé de données. Celle-ci est désenveloppée **une fois** par déverrouillage, puis tout est chiffré en logiciel, en mémoire. Aucune opération Keystore pendant la frappe. | Chaque appel au Keystore passe par un aller-retour vers le service système, ce qui coûte plusieurs millisecondes. Risque résiduel : la clé de données reste en mémoire du processus tant que l'appareil est déverrouillé. Elle est effacée à l'extinction de l'écran. |
| D7 | Bulle d'aperçu, zones de toucher dynamiques et son de touche (prompt réactivité) contre « aucun retour visuel agrandi » dans les champs sensibles. | Dans les **champs sensibles**, le **mode incognito** et le **pavé PIN** : pas de bulle, pas de zone dynamique, pas de son, pas de surbrillance prolongée, pas de modèle de toucher appris. | Limite l'espionnage par-dessus l'épaule et les canaux auxiliaires. |
| D8 | Modèle de toucher personnel, bigrammes appris et statistiques (inspirés de SwiftKey) contre « ne rien apprendre de secret ». | Tout apprentissage passe par **`LearningGate`** : il vérifie le type de champ, l'incognito par application, `NO_PERSONALIZED_LEARNING` et le détecteur de secrets. Le résultat est stocké chiffré, visible dans le tableau de transparence et effacé par le geste panique. Les statistiques sont désactivées par défaut et ne contiennent que des compteurs, jamais de texte. | — |
| D9 | Le compteur « octets envoyés : 0 » doit être **vérifiable**. | Le tableau de bord affiche deux preuves réelles : (a) l'absence de `INTERNET` dans `PackageManager.getPackageInfo(..., GET_PERMISSIONS).requestedPermissions` ; (b) `TrafficStats.getUidTxBytes(Process.myUid())`, avec mention explicite quand la valeur n'est pas prise en charge. **Aucun compteur maison.** | Un compteur interne prouverait seulement que le code se déclare honnête. Ce serait du théâtre. |
| D10 | Dictée vocale. | L'actuel `RecognizerIntent` **délègue l'audio au service de reconnaissance installé, qui peut être en ligne**. Avant le premier usage, afficher un avertissement explicite. La dictée est désactivée en champ sensible et en incognito. Le modèle Whisper hors ligne est reporté (§8). | Être honnête sur la vie privée. |
| D11 | Effacer zeroize les secrets côté JVM. | Effacement **au mieux** : les clés et tampons déchiffrés vivent dans des `ByteArray` et `CharArray` remis à zéro après usage. Le texte qui transite par `InputConnection` est forcément en `String`. On le documente comme risque résiduel ; cette copie existe de toute façon dans l'application cible. | Être honnête sur les limites de la plateforme. |
| D12 | Validation de la frappe à l'appui (comportement actuel) ou au relâchement (FUTO, ASK, SwiftKey). | **Au relâchement par défaut**, avec validation anticipée (« phantom up ») quand un second doigt se pose. L'option « à l'appui » reste disponible. On mesure les deux. | Permet de corriger une frappe en glissant le doigt, sans perdre en réactivité ressentie : le retour visuel et haptique arrive dès l'appui. |

---

## 3. Contraintes non négociables

- **Aucune permission réseau.** `INTERNET` est absente du **manifeste fusionné**, et la CI le vérifie (§9). Tout ce qui a besoin du réseau ira dans une **application compagnon séparée** (un autre APK et un autre nom de paquet, car les permissions s'appliquent à toute une application), en opt-in. Elle est hors de ce lot.
- **Zéro télémétrie, zéro analytics, zéro outil de rapport de plantage, zéro SDK tiers propriétaire.**
- **Champs sensibles**, détectés par une classe unique `SecurityPolicy` :
  - variations `TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD` et `TYPE_NUMBER_VARIATION_PASSWORD` ;
  - drapeau `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, que Chrome pose par exemple en navigation privée ;
  - drapeau `TYPE_TEXT_FLAG_NO_SUGGESTIONS` ;
  - liste incognito par application.

  Le tableau ci-dessous précise ce qui est coupé selon le cas :

  | Fonction | Champ mot de passe | `NO_PERSONALIZED_LEARNING` ou application incognito |
  |----------|:-:|:-:|
  | Suggestions | coupées | **conservées** (prédiction depuis les dictionnaires embarqués) |
  | Apprentissage | coupé | coupé |
  | Historique des emoji | coupé | coupé |
  | Presse-papiers | coupé | coupé |
  | Bulle d'aperçu | coupée | coupée |
  | Son de touche | coupé | coupé |
  | Dictée | coupée | coupée |
  | Traduction | coupée | coupée |
- **Données au repos chiffrées** : mots appris, bigrammes, modèle de toucher, presse-papiers, extraits de texte (snippets), emoji récents. Chiffrement AES-256-GCM, clé maître dans Android Keystore (§6, phase 3).
- **Hygiène des journaux** :
  - aucune donnée tapée dans `Log`, `println`, `Trace`, les messages d'exception ou `Toast`, **même en debug** ;
  - en release, R8 supprime `android.util.Log` (`-assumenosideeffects`) ;
  - une règle Lint ou detekt maison fait échouer le build en cas d'appel de journalisation avec un argument non constant dans les paquets `ime`, `keyboard`, `engine` et `security`.
- **Sauvegardes exclues** :
  - `allowBackup="false"` **ne suffit pas** à partir d'Android 12 : le transfert d'appareil à appareil reste actif. Il faut référencer `android:dataExtractionRules` et y exclure tous les domaines, dans `<cloud-backup>` comme dans `<device-transfer>`. Il faut aussi référencer `fullBackupContent` pour les versions antérieures.
  - Le fichier actuel `data_extraction_rules.xml` est le modèle vide d'Android Studio et **n'est pas référencé** dans le manifeste.
- **`FLAG_SECURE`** sur toute activité qui affiche des données apprises ou copiées : réglages, tableau de transparence, extraits de texte. Évalue aussi sa pose sur la fenêtre de l'IME quand le panneau presse-papiers ou extraits est ouvert, et documente la limite constatée.
- **Pas de chargement de code dynamique**, pas de WebView, pas de réflexion sur des entrées, pas de désérialisation de données non fiables sans parseur strict borné en taille.
- **Ne rien casser.** Les fonctions de `README.md` survivent : emoji, presse-papiers, traduction hors ligne, thèmes, image de fond, modes une main, réglage de hauteur. Les tests existants sont adaptés, pas supprimés.

---

## 4. Diagnostic de l'existant

### 4.1 Réactivité

| # | Constat | Où |
|---|---------|----|
| R1 | **Environ 30 % de la zone des lettres ne tape rien.** Les touches font 58 × 85 sur un pas de 68 × ~105 (repère 684). Un appui dans un écart ne touche aucune vue, et le clavier le consomme sans rien taper. | `MintKeyboard.kt:323-326`, `:136-139` |
| R2 | **`rebuild()` recrée toutes les vues** à chaque Maj, au retour en minuscule, et 2 à 3 fois par affichage (`configure` est appelé dans `onStartInput` et `onStartInputView`, `refreshTheme` dans `onWindowShown`). | `MintKeyboard.kt:285-302`, `:248`, `:266` ; `MintInputService.kt:56-58` |
| R3 | **Lectures IPC synchrones à chaque frappe** (`getTextBeforeCursor`, `getSelectedText`). | `MintInputService.kt:87`, `:107`, `:115`, `:121`, `:147`, `:204` |
| R4 | **Correction parfois différée** : le mot fautif est écrit, puis remplacé, et le texte saute. | `MintInputService.kt:126-143` |
| R5 | **Bandeau de suggestions en retard d'environ 200 ms** (attentes de 85 ms et 90 ms qui s'additionnent), reconstruit vue par vue. | `MintInputService.kt:81` ; `MintKeyboard.kt:76-96` |
| R6 | **Correcteur coûteux** : `candidates()` est appelé 2 fois par frappe, `fold()` compile une `Regex` à chaque appel, la boucle chaude alloue. | `FrenchCorrector.kt:99-101`, `:62`, `:132` |
| R7 | **Démarrage lourd** : un trie de `HashMap` de 50 000 mots est construit à chaque création du service. | `MintInputService.kt:46-51` |
| R8 | **Apprentissage qui réécrit tout le fichier de réglages** ; `personal()` est relu à chaque espace. | `UserLexicon.kt:10-23` ; `MintInputService.kt:75` |
| R9 | **Latence mesurée trop optimiste** : `uptimeMillis()` au lieu de `event.eventTime`. | `InputLatency.kt` |
| R10 | **Build non optimisé** : R8 désactivé, pas de Baseline Profile, Compose embarqué mais inutilisé. | `app/build.gradle.kts` |
| R11 | **Allocations dans les chemins chauds** : `setOf` et `Typeface` par touche, `vararg` dans `onDraw`, préférence relue à chaque appui. | `MintKeyboard.kt:628-629`, `:675`, `:194` |

### 4.2 Agrément

| # | Constat | Où |
|---|---------|----|
| A1 | **Pas de majuscule automatique.** | absent |
| A2 | **Typographie française inversée** : le code supprime l'espace avant `! ? ; :`. | `MintInputService.kt:111` |
| A3 | **Bandeau sans indication de la correction** que l'espace va appliquer, sans le mot tapé tel quel, sans la meilleure suggestion au centre. | `MintKeyboard.kt:275-276` |
| A4 | **Le mot restauré par Retour arrière n'est pas appris.** | `MintInputService.kt:145-149` |
| A5 | **Prédiction du mot suivant figée**, codée en dur (23 entrées). | `FrenchCorrector.kt:122-131` |
| A6 | **Distance d'édition aveugle à la disposition AZERTY** et aux coordonnées du toucher. | `FrenchCorrector.kt:53` |
| A7 | **Validation à l'appui sans glissement possible** ; contournement multi-doigts fragile. | `MintKeyboard.kt:100-130`, `:194` |
| A8 | **Pas de pavé tactile sur Espace, pas de suppression par mot** ; répétition de Retour arrière à vitesse fixe. | `MintKeyboard.kt:204-217` |
| A9 | **Retour sensoriel limité** : `KEYBOARD_TAP` seulement, pas de son, pas d'aperçu. | `MintKeyboard.kt:115`, `:194` |
| A10 | **Délai d'appui long fixe** (350 ms), pas de rangée de chiffres. | `MintKeyboard.kt:194`, `:322` |

### 4.3 Sécurité et vie privée

| # | Constat | Où | Gravité |
|---|---------|----|---------|
| S1 | **Le drapeau `IME_FLAG_NO_PERSONALIZED_LEARNING` est ignoré.** Keyra apprend donc les mots tapés dans un onglet de navigation privée de Chrome, ou dans toute application qui demande à ne pas être apprise. | `MintInputService.kt:67-73` | Haute |
| S2 | **Tout mot inconnu de 2 lettres ou plus tapé dans un champ texte normal est appris**, sans détection de secrets : mot de passe tapé dans un champ ordinaire, code, jeton, numéro de carte découpé. | `MintInputService.kt:120`, `:127`, `:134` ; `UserLexicon.kt` | Haute |
| S3 | **Historique du presse-papiers en clair**, en JSON dans les SharedPreferences, sans expiration. Il capture toute copie non marquée sensible. Avant Android 13, ou si l'application source ne pose pas `EXTRA_IS_SENSITIVE`, un mot de passe copié depuis un gestionnaire y reste jusqu'à 20 copies plus tard. | `ClipboardHistory.kt` ; `MintInputService.kt:19`, `:58` | Haute |
| S4 | **Mots appris, mots personnels et emoji récents stockés en clair.** | `UserLexicon.kt`, `EmojiHistory.kt`, `KeyboardPrefs` | Moyenne |
| S5 | **Transfert d'appareil à appareil non exclu**, voir §3. | `AndroidManifest.xml` ; `res/xml/data_extraction_rules.xml` | Moyenne |
| S6 | **Les réglages affichent les mots personnels sans `FLAG_SECURE`.** Ils sont visibles dans la vue des applications récentes et sur les captures d'écran. | `SettingsActivity.kt:46` | Moyenne |
| S7 | **Dictée via `RecognizerIntent`** : l'audio part vers le service installé, qui peut être en ligne, sans avertissement. | `MediaInputActivity.kt` | Moyenne |
| S8 | **APK distribué signé avec une clé de développement** (README). Pas de signature de release, pas de rotation de clé, pas de build reproductible, pas de `gradle/verification-metadata.xml`. Le plugin `foojay-resolver` télécharge un JDK au build. Le wrapper Gradle est bien épinglé par SHA-256 : à conserver. | `README.md`, `settings.gradle.kts` | Moyenne |
| S9 | **`PendingInput` est un singleton global.** Le résultat d'une dictée ou d'une image est injecté dans le premier champ de l'application cible qui reçoit le focus, pas forcément celui d'origine. | `MediaInputActivity.kt:10-13` ; `MintInputService.kt:227-242` | Basse |
| S10 | **Pas de prise en compte de l'état verrouillé.** Le clavier peut être utilisé sur l'écran de verrouillage (réponse à une notification). Il faut décider quoi apprendre et afficher dans cet état : pas de presse-papiers, pas d'extraits de texte. | absent | Moyenne |

À conserver : pas de permission `INTERNET`, `allowBackup="false"`, respect de `EXTRA_IS_SENSITIVE`, service IME protégé par `BIND_INPUT_METHOD`, activités internes non exportées.

---

## 5. Références : ce qu'on emprunte

| Référence | Licence | Ce qu'on reprend |
|-----------|---------|------------------|
| **AOSP LatinIME** | Apache 2.0 | Vue Canvas unique, `KeyDetector` par proximité (pas de zone morte), un `PointerTracker` par doigt (glissement, phantom up), `RichInputConnection` (cache local du texte), *composing text*, chaîne de suggestion avec seuil d'auto-correction, annulation par Retour arrière. Extraits autorisés avec attribution. |
| **FUTO Keyboard** | FUTO Source First (**idées seulement**) | Philosophie 100 % hors ligne, pavé tactile sur Espace, vibration réglable, barre d'actions, modèle de langage local (reporté). |
| **AnySoftKeyboard** | Apache 2.0 | Dictionnaire « mot suivant » appris localement, gestes configurables (balayages), touches à appui long riches, délais réglables, son et vibration séparés, frappe gestuelle (reportée). |
| **SwiftKey** | propriétaire (**comportements seulement**) | Bandeau à 3 suggestions avec la meilleure au centre, en gras si l'espace va l'appliquer. Zones de toucher dynamiques invisibles. Modèle de toucher personnel. Rangée de chiffres optionnelle. Épinglage dans le presse-papiers. |

---

## 6. Objectifs chiffrés (Pixel 9a, build release)

| Mesure | Cible |
|--------|-------|
| `ACTION_DOWN` (`event.eventTime`) → touche affichée enfoncée | ≤ 8,3 ms p95 (une image à 120 Hz) |
| Traitement de la frappe → `commitText` ou `setComposingText` | ≤ 2 ms p95, **0 lecture IPC** |
| Frappe → bandeau de suggestions à jour | ≤ 40 ms p95 |
| `candidates()` sur un mot de 6 lettres | ≤ 3 ms p95, 0 allocation par nœud |
| Auto-correction à l'espace | synchrone, sans saut de texte |
| Allocations pendant la frappe (toucher et dessin) | 0 par frappe |
| `rebuild()` complets en tapant des lettres, Maj comprise | 0 |
| Frappes perdues dans les écarts entre touches | 0 % |
| Démarrage à froid du service → première image du clavier | ≤ 150 ms |
| Mémoire (PSS) du processus IME, en frappe normale | ≤ 80 Mo |
| Opérations Android Keystore pendant la frappe | 0 |
| Données tapées dans logcat après 10 minutes de saisie (test automatisé) | 0 occurrence |
| Données utilisateur lisibles en clair dans `/data/data/<paquet>/` | 0 fichier |
| Arrêt forcé (`kill -9`) pendant une écriture | aucune corruption : l'état précédent ou le nouvel état se relit |

---

## 7. Plan par phases

**Règle d'or : à la fin de chaque phase, tu t'arrêtes, tu présentes un rapport et tu attends ma validation avant la phase suivante.**

Le rapport contient :

- ce qui a été fait ;
- les mesures du §6, avant et après ;
- les tests verts ;
- pour **chaque fichier créé ou fortement modifié**, une courte section « Considérations de sécurité » qui liste ce qui pourrait mal tourner.

À l'intérieur d'une phase : **un commit par sous-étape**, avec un message en français. Lance ensuite `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

### Phase 0 — Conception et mesure de référence (aucun changement fonctionnel)

1. Lance `git init`. Ajoute un `.gitignore` propre qui exclut `build/`, `.gradle/`, `.kotlin/`, `.idea/` et `local.properties`. Fais un commit initial de l'état 11.0.
2. **Modèle de menace STRIDE** dans `docs/threat-model.md`. Couvre au minimum :
   - l'exfiltration réseau, y compris par une dépendance compromise ;
   - l'accès physique à un appareil déverrouillé (regard par-dessus l'épaule, vol) ;
   - un logiciel malveillant local qui lit le stockage, le presse-papiers ou les journaux ;
   - l'apprentissage involontaire de secrets ;
   - une mise à jour malveillante (compte mainteneur compromis, build non reproductible) ;
   - les fuites par rapports de plantage, journaux, captures de la vue des applications récentes, sauvegarde cloud ou transfert d'appareil ;
   - les applications cibles hostiles (`EditorInfo` mensonger, `InputConnection` qui renvoie des données piégées ou énormes) ;
   - l'usage sur l'écran de verrouillage.

   Pour chaque menace : la contre-mesure, la phase où elle est implémentée et le **risque résiduel**. Relie chaque constat S1 à S10 à une menace.
3. **Diagramme d'architecture** en Mermaid dans `docs/architecture.md` : modules, **frontières de confiance** (application cible ↔ `InputConnection` ↔ IME ↔ stockage chiffré ↔ Keystore) et flux de données. Indique où les données tapées existent en clair et pendant combien de temps.
4. **ADR** dans `docs/adr/` : un par arbitrage D1 à D12, plus un ADR par dépendance conservée ou ajoutée.
5. **Arborescence cible** (§10) et plan de migration fichier par fichier.
6. **Mesures de référence** :
   - `InputLatency` corrigé pour mesurer depuis `event.eventTime`, avec percentiles p50 et p95 dans un tampon circulaire, **sans aucun contenu tapé** ;
   - sections `Trace` aux **noms constants** ;
   - module `:benchmark` (Macrobenchmark) qui tape 200 caractères dans `MainActivity` ;
   - jeu de qualité `fr_typos.tsv` (200 fautes réalistes) avec le taux de correction actuel.

   Résultats dans `PERF.md`.

➡️ **Arrêt : validation.**

### Phase 1 — Durcissement rapide, build et CI

1. **`SecurityPolicy`** (correction de S1) : une classe pure, testable sur la JVM, construite depuis `EditorInfo`. Elle expose `isPassword`, `noLearning`, `noSuggestions`, `noClipboard`, `noPreview`, `isPinPad`, `isLocked`. Elle utilise `KeyguardManager.isKeyguardLocked` et `isDeviceLocked`. Tout le code lit cette politique ; plus aucun test d'`inputType` dispersé.
2. **`dataExtractionRules` et `fullBackupContent`** référencés dans le manifeste, avec exclusion totale (correction de S5).
3. **`FLAG_SECURE`** sur `SettingsActivity` (correction de S6).
4. **Avertissement de dictée** (correction de S7) et **`PendingInput` lié à la session** (correction de S9). On ajoute un jeton de session, un délai de validité de 60 s et une vérification du champ d'origine (`fieldId` et `packageName`). En cas d'échec, rien n'est inséré.
5. **Build** :
   - activer R8 en release, avec les règles de conservation de l'IME et `-assumenosideeffects` pour `android.util.Log` ;
   - retirer Compose ;
   - `dependenciesInfo { includeInApk = false; includeInBundle = false }`, exigé par F-Droid ;
   - `gradle/verification-metadata.xml` avec des sommes SHA-256 ;
   - remplacer `foojay-resolver` par une toolchain JDK installée et documentée ;
   - `minSdk 29` si D4 est validé.
6. **Règle de lint maison** : pas de `Log`, `println`, `Trace`, `Toast` ni de message d'exception construit à partir d'une variable dans les paquets sensibles. Ajouter detekt, Android Lint en mode strict (`warningsAsErrors`) et Semgrep avec des règles Android.
7. **CI** : la forge reste à choisir, GitHub est nécessaire pour OpenSSF Scorecard. Les jobs :
   - lint ;
   - tests JVM ;
   - tests instrumentés sur émulateur ;
   - **vérification du manifeste fusionné** : `aapt2 dump permissions` sur l'APK release, échec si une permission ne figure pas dans la liste blanche `docs/allowed-permissions.txt` (vide aujourd'hui) ;
   - **build reproductible** : deux builds dans deux environnements propres, puis comparaison avec `diffoscope` ou `apksigcopier compare` ;
   - CodeQL (Kotlin) ;
   - génération d'un SBOM CycloneDX.

➡️ **Arrêt : validation.**

### Phase 2 — Moteur de toucher et de rendu

1. **`KeyboardLayout` déclaratif.** On commence par l'AZERTY intégré en Kotlin ; le format JSON viendra en phase 6. La variante majuscule ne change que les libellés dessinés.
2. **`KeyboardView`**, une seule vue Canvas :
   - `Paint`, `Path`, `Typeface` et tailles précalculés une fois par disposition et par taille ;
   - fond mis en cache, redessin partiel ;
   - Maj = changer un drapeau et invalider la vue, **jamais `rebuild()`**.
3. **`KeyDetector` par proximité** (correction de R1) : touche la plus proche, grille précalculée, recherche en O(1). Zones dynamiques (SwiftKey) en option, plafonnées à 25 %, **désactivées si `SecurityPolicy` l'exige**.
4. **`PointerTracker` par doigt**, selon D12 :
   - retour visuel, haptique et son dès l'appui ;
   - glissement de correction pendant le mouvement ;
   - glissement depuis `?123` ou Maj vers un caractère ;
   - phantom up quand un second doigt se pose ;
   - mémorisation des coordonnées de chaque lettre du mot en cours, **uniquement en mémoire** et effacées à la fin du mot.
5. **Appui long** à délai réglable (200 à 600 ms, 300 ms par défaut), touches secondaires dessinées par la même vue.
6. **Bandeau de suggestions** qui ne déclenche jamais de `requestLayout`.
7. **Accessibilité** : `ExploreByTouchHelper` reprend les `contentDescription` actuelles. Dans un champ mot de passe, TalkBack annonce « point » au lieu du caractère, sauf si l'utilisateur a activé l'énoncé des mots de passe dans les réglages d'accessibilité Android. Adapter `FeatureTest`.
8. **Pavé PIN anti-regard** (fonction innovante n° 5), en option, pour `isPinPad` : disposition mélangée par `SecureRandom` à chaque ouverture, sans surbrillance, sans bulle, sans son. Haptique conservé, au choix de l'utilisateur.
9. **Ne reconstruire qu'en cas de vrai changement** : thème, taille ou rotation, détectés par une empreinte.

**Critères** :

- 0 % de frappes perdues (test JVM sur toute la surface et test instrumenté dans les écarts) ;
- 200 lettres tapées en alternant deux doigts, ordre conservé ;
- 0 `rebuild()` en tapant `Bonjour Maman` ;
- p95 de l'appui à l'affichage ≤ 8,3 ms.

➡️ **Arrêt : validation.**

### Phase 3 — Stockage chiffré, presse-papiers et défenses de vie privée

1. **`KeyManager`** :
   - **Clé maître** : AES-256 dans `AndroidKeyStore`, créée avec `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`, `setBlockModes(GCM)`, `setEncryptionPaddings(NONE)`, `setKeySize(256)`, `setIsStrongBoxBacked(true)` (API 28 ; repli si `StrongBoxUnavailableException`) et `setUnlockedDeviceRequired(true)` (API 28).
   - **Clé de données** : 256 bits tirés par `SecureRandom`, enveloppée par la clé maître et stockée dans `keys.bin`.
   - Elle est désenveloppée **une fois** après déverrouillage, gardée dans un `ByteArray`, et **mise à zéro** à `ACTION_SCREEN_OFF` (récepteur dynamique) et à `onDestroy`.
   - Quand l'appareil est verrouillé : aucun apprentissage. Les événements sont gardés en mémoire, filtrés par `LearningGate`, puis écrits au déverrouillage.
2. **`EncryptedStore`** :
   - chiffrement AES-256-GCM (`javax.crypto`), nonce aléatoire de 96 bits, données associées = `type ‖ version ‖ nom du fichier` ;
   - photo complète écrite par `AtomicFile`, plus un **journal en ajout seul** d'enregistrements chiffrés, compacté au-delà de 256 Ko ;
   - au chargement : photo, puis rejeu du journal. Un enregistrement final tronqué est ignoré, jamais fatal ;
   - parseur borné en taille, **fuzzé** (Jazzer).
3. **Migration** : les données en clair de 11.0 (`learned_words`, `personal`, `clipboard_history`, `emoji_history`) sont importées dans le stockage chiffré, puis supprimées des SharedPreferences. On documente que l'effacement sur mémoire flash n'est pas garanti : c'est un risque résiduel, d'où le crypto-shredding.
4. **`SecretDetector`** (fonction innovante n° 1), local et déterministe. Il signale :
   - une suite de 13 à 19 chiffres, espaces et tirets compris, valide selon Luhn ;
   - un IBAN valide (modulo 97) ;
   - un code OTP de 4 à 8 chiffres ;
   - un jeton de 12 caractères ou plus mêlant plusieurs classes de caractères avec une entropie de Shannon supérieure à environ 3,5 bits par caractère (seuil à calibrer sur un corpus de vrais mots) ;
   - un préfixe connu de clé : `AKIA`, `ghp_`, `github_pat_`, `glpat-`, `sk-`, `xox[baprs]-`, `AIza`, `-----BEGIN` ;
   - toute chaîne contenant un chiffre (règle simple : on n'apprend jamais un mot qui contient un chiffre) ;
   - les adresses e-mail et numéros de téléphone, qui ne sont pas appris.

   Il s'applique au mot validé, aux bigrammes et à chaque copie du presse-papiers. Ajouter des tests par propriétés (jqwik) et un corpus de faux positifs (mots français rares qui ne doivent pas être bloqués).
5. **`LearningGate`** : seul point d'entrée vers tout apprentissage, avec `SecurityPolicy`, `SecretDetector`, incognito et état verrouillé (correction de S2).
6. **Presse-papiers éphémère** (fonction innovante n° 3, correction de S3) :
   - historique chiffré et désactivable ;
   - expiration réglable : 1 h par défaut pour une copie normale, **30 s** pour une copie sensible (`EXTRA_IS_SENSITIVE` ou signalée par `SecretDetector`), et une copie sensible n'est jamais écrite sur disque ;
   - épinglage possible, sauf pour une copie sensible ;
   - option opt-in « effacer le presse-papiers système après 30 s pour une copie sensible » : `clearPrimaryClip()` (API 28), **seulement si le contenu est toujours le même**, pour ne pas écraser une copie plus récente de l'utilisateur ;
   - aucun accès au presse-papiers depuis l'écran de verrouillage.
7. **Incognito par application** (fonction innovante n° 4) :
   - la liste repose sur `EditorInfo.packageName` ;
   - pré-remplissage intelligent **sans `QUERY_ALL_PACKAGES`** : une liste embarquée de paquets connus (gestionnaires de mots de passe, messageries chiffrées, banques françaises courantes ; **vérifie chaque nom de paquet**), comparée quand l'application est rencontrée ;
   - **proposition automatique** d'ajouter à la liste toute application où un champ mot de passe a été vu ;
   - indicateur discret (icône dans le bandeau) quand l'incognito est actif.
8. **Geste panique** (fonction innovante n° 6) :
   - le geste est configurable, par exemple un appui long de 3 s sur la touche menu puis une confirmation glissée ;
   - il exécute `KeyStore.deleteEntry(alias)`, puis supprime les fichiers, met à zéro la clé de données et vide les caches mémoire ;
   - enfin, `Process.killProcess(myPid())` purge le tas ; le système relance l'IME ;
   - une nouvelle clé est créée au prochain usage ;
   - test instrumenté : après le geste, aucune donnée n'est relisible, même en restaurant les anciens fichiers.

**Critères** :

- aucun fichier lisible en clair ;
- test `kill -9` pendant une écriture : l'état se relit, sans corruption ;
- test de navigation privée simulée (`IME_FLAG_NO_PERSONALIZED_LEARNING`) : 0 mot appris ;
- 0 opération Keystore pendant la frappe (trace Perfetto).

➡️ **Arrêt : validation.**

### Phase 4 — Logique de saisie

1. **`RichInputConnection`** (correction de R3) :
   - cache local d'environ 1 000 caractères avant le curseur, rempli une fois à `onStartInputView` ;
   - mis à jour par chaque commit et par `onUpdateSelection` ;
   - relecture uniquement si la position ne correspond pas à celle attendue ;
   - **les réponses de l'application sont bornées en taille et traitées comme non fiables**.
2. **Composing text** activé par défaut. Mode « commit direct » pour les applications qui le gèrent mal, sur liste blanche.
3. **Majuscule automatique** (correction de A1) via `getCursorCapsMode`, calculée sur le cache, en respectant `CAP_SENTENCES`, `CAP_WORDS` et `CAP_CHARACTERS`.
4. **Typographie française** (correction de A2) :
   - espace fine insécable (U+202F) avant `? ! ; :`, avec U+00A0 en option ;
   - guillemets `« »` ;
   - désactivée dans les champs e-mail, URL et mot de passe.
5. **Auto-correction synchrone** (correction de R4) : attente bornée à 30 ms sur le calcul en cours. Suppression du chemin « écrire puis remplacer plus tard ».
6. **Annulation et apprentissage** (correction de A4) : le mot restauré passe par `LearningGate`, puis il est ajouté au dictionnaire personnel et mis sur liste noire de correction pour la session.
7. **Retour arrière** (correction de A8) : répétition qui accélère, passage mot par mot après environ 1,5 s, glissement vers la gauche pour sélectionner des mots à effacer.
8. **Pavé tactile sur Espace** ; appui long sur Espace = sélecteur de clavier.
9. **Gestes configurables** (ASK) : vers le bas = masquer, vers le haut = Maj, vers la gauche sur les lettres = supprimer le mot (désactivé par défaut).
10. **Double espace = point**, avec une fenêtre de 600 ms entre les deux espaces.
11. **`Settings` immuable** : les préférences sont relues uniquement dans le listener de changement ; les mots personnels sont gardés dans un `HashSet` (correction de R8 et R11).

➡️ **Arrêt : validation.**

### Phase 5 — Prédiction et correction locales

1. **Dictionnaire binaire précompilé au build** (corrections de R6 et R7) :
   - une tâche Gradle convertit `fr_frequency.txt` en `fr_main.dict` : trie compact (double-array ou LOUDS), formes sans accents précalculées, fréquences en échelle logarithmique sur 8 bits ;
   - fichier non compressé dans l'APK (`noCompress`) et mappé en mémoire (`FileChannel.map`) ;
   - **en-tête avec nombre magique, version et taille**, et un parseur qui rejette toute incohérence ;
   - parseur fuzzé.
2. **Recherche sans allocation** : matrice préallouée, **un seul passage** par frappe pour les candidats et la décision de correction.
3. **Distance pondérée par la disposition AZERTY** (correction de A6). Coûts :
   - touche voisine : 0,5 ;
   - accent manquant : 0,1 ;
   - apostrophe manquante : 0,2 ;
   - frappe doublée : coût réduit.

   Option spatiale : remplacer le coût de substitution par `-log P(touche | x, y)`, avec une gaussienne centrée sur chaque touche.
4. **Modèle de toucher personnel (SwiftKey)** : moyenne mobile du décalage `(dx, dy)` par touche, mise à jour **seulement pour les mots validés sans correction, via `LearningGate`**. Stockage chiffré, visible dans le tableau de transparence, effaçable.
5. **Score** : `coût − α·log(fréquence) − β·log P(mot | mot précédent) − γ·bonus personnel`. La tolérance de 0 à 100 est conservée et reliée à un seuil de confiance. Le jeu `fr_typos.tsv` doit faire mieux que la référence de la phase 0, avec **0 correction de mot valide**.
6. **Prédiction du mot suivant** (correction de A5) :
   - bigrammes embarqués `fr_bigrams.dict`, issus d'un corpus de licence compatible documentée dans `ATTRIBUTIONS.txt` ;
   - bigrammes appris via `LearningGate` et `EncryptedStore`, avec vieillissement exponentiel, plafonnés à 20 000.
7. **Bandeau façon SwiftKey** (correction de A3) :
   - centre : meilleur candidat, en gras s'il sera appliqué ;
   - gauche : le mot tapé tel quel, entre guillemets ;
   - droite : la deuxième suggestion ;
   - appui long sur une suggestion : « Ne plus proposer » ;
   - le bandeau ne bouge pas tant qu'un doigt est posé dessus ;
   - les requêtes périmées sont abandonnées.
8. **Suggestion d'emoji** sur une annotation CLDR exacte, désactivable.

➡️ **Arrêt : validation.**

### Phase 6 — Agrément et fonctions innovantes restantes

1. **Haptique** :
   - mode « système » : `KEYBOARD_PRESS` et `KEYBOARD_RELEASE` ;
   - mode « personnalisé » : `VibrationEffect.Composition` avec `PRIMITIVE_TICK` ou `PRIMITIVE_CLICK` (API 30) et une intensité réglable.

   **Son** : `AudioManager.playSoundEffect` avec `FX_KEYPRESS_*`, coupé selon `SecurityPolicy`. **Bulle d'aperçu** optionnelle (`PopupWindow` préallouée), coupée selon `SecurityPolicy`. **Rangée de chiffres** optionnelle.
2. **Tableau de bord de transparence** (fonction innovante n° 2) : une activité avec `FLAG_SECURE` pour lister, rechercher, modifier et supprimer les mots appris, les bigrammes, le modèle de toucher, le presse-papiers, les extraits de texte et la liste incognito. Elle affiche les preuves réseau décrites en D9. Ses boutons « Tout effacer » et « Exporter » sont protégés par une confirmation.
3. **Extraits de texte et expansion chiffrés** (fonction innovante n° 7) :
   - un raccourci tapé dans le bandeau propose son extrait ;
   - coffre optionnel protégé par biométrie, avec une clé à `setUserAuthenticationRequired(true)` et `setUserAuthenticationParameters(timeout, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` (API 30) ;
   - un IME ne pouvant pas afficher lui-même de manière fiable une invite biométrique, passe par une **activité transparente dédiée**, sur le modèle de `MediaInputActivity`. Vérifie le comportement réel sur GrapheneOS et documente-le ;
   - extraits indisponibles quand l'appareil est verrouillé.
4. **Export et import chiffrés** (fonction innovante n° 10, version sans synchronisation) :
   - format : `magic ‖ version ‖ sel ‖ paramètres Argon2id ‖ nonce ‖ texte chiffré AES-256-GCM` ;
   - **Argon2id d'une bibliothèque éprouvée** (Bouncy Castle, API légère, version épinglée, avec ADR), **jamais réimplémenté** ;
   - paramètres minimaux imposés à l'import (mémoire ≥ 64 Mio, itérations ≥ 3) ;
   - taille bornée, parseur fuzzé ;
   - fichier choisi par l'utilisateur via le sélecteur système (`ACTION_CREATE_DOCUMENT` et `ACTION_OPEN_DOCUMENT`) depuis une activité, jamais depuis l'IME.
5. **Dispositions multiples** :
   - format JSON déclaratif validé par un **parseur strict maison** sur `org.json` : schéma explicite, champs inconnus rejetés, tailles et nombres de touches bornés, codes de touche dans une liste blanche ;
   - parseur fuzzé ;
   - dispositions livrées : AZERTY (par défaut), BÉPO, QWERTY, QWERTZ et Dvorak ;
   - aucune disposition chargée depuis l'extérieur dans ce lot.
6. **Presse-papiers** : épingler un élément, supprimer un élément par appui long.

➡️ **Arrêt : validation.**

### Phase 7 — Durcissement et publication

1. **Auto-audit OWASP MASVS v2, profil MAS-L2** : une checklist dans `docs/masvs-audit.md`, avec preuve ou justification pour chaque exigence. Corrige ce qui est trouvé.
2. **Baseline Profile** (`androidx.profileinstaller`, avec ADR). Vérifie que le build reste reproductible avec le profil.
3. **Signature** :
   - clé de release hors du dépôt ;
   - signature APK v3 avec possibilité de rotation (v3.1, API 33) ;
   - artefacts de release signés avec Sigstore (`cosign`) ;
   - SBOM CycloneDX jointe à chaque release.
4. **Métadonnées F-Droid** (`fastlane/metadata/android/fr-FR/`), à faire vérifier par un build F-Droid local.
5. **`SECURITY.md`** : divulgation coordonnée, clé PGP, délais de réponse. **`CONTRIBUTING.md`** : branche protégée, commits signés, revue obligatoire, ADR obligatoire pour toute nouvelle dépendance. Viser **OpenSSF Scorecard ≥ 8** si le dépôt est sur GitHub.
6. **Documentation utilisateur** : `README.md`, page Confidentialité du clavier et notes de version 12.0 mises à jour. Elles disent honnêtement les limites : dictée système, effacement mémoire au mieux sur la JVM, clé de données en mémoire pendant que l'appareil est déverrouillé.

➡️ **Arrêt : rapport final.**

---

## 8. Reports justifiés (hors de ce lot)

Chaque report doit être confirmé ou contesté dans un ADR de la phase 0.

| Fonction | Raison du report | Condition pour la reprendre |
|----------|------------------|-----------------------------|
| Frappe gestuelle | Gros chantier. L'architecture de la phase 2 la prépare (trajectoires du `PointerTracker`, moteur qui accepte des suites de points). | Phases 2 et 5 stables. |
| Petit modèle de langage local de moins de 20 Mo | Il faut un moteur natif (JNI, avec ADR, fuzzing et peut-être Rust), et le budget de 16 ms par frappe sur un appareil d'entrée de gamme reste à prouver. Les n-grammes de la phase 5 d'abord. | Les n-grammes ont atteint leur plafond de qualité mesuré. |
| Dictée Whisper hors ligne | Elle dépend de l'**application compagnon** (téléchargement du modèle et vérification de son empreinte), qui n'existe pas encore. | Application compagnon conçue, avec son propre modèle de menace. |
| Synchronisation chiffrée de bout en bout via l'application compagnon | Même raison. L'export et l'import chiffrés de la phase 6 couvrent le besoin sans réseau. | Idem. |
| Multilingue avec bascule automatique | Il faut des dictionnaires par langue et un détecteur de langue local. Les dispositions de la phase 6 n'en dépendent pas. | Dictionnaire anglais libre trouvé, avec licence vérifiée. |
| Clavier flottant | Délicat avec `onComputeInsets` et les zones tactiles ; faible priorité pour un téléphone. | Demande explicite. |

---

## 9. Qualité et tests

- **Couverture** : Kover ≥ 90 % sur les paquets `engine`, `security` et `storage`.
- **Tests par propriétés** (jqwik ou Kotest) :
  - correcteur : jamais de correction d'un mot du dictionnaire ;
  - `SecretDetector` ;
  - aller-retour de `EncryptedStore` ;
  - `KeyDetector` : couverture totale de la surface.
- **Fuzzing** (Jazzer, en tests JVM et en job CI nocturne) : dictionnaire binaire, fichiers de `EncryptedStore`, dispositions JSON, fichier d'import.
- **Tests instrumentés** :
  - champs mot de passe réels : 0 apprentissage, 0 suggestion, 0 presse-papiers, pas de bulle ;
  - `IME_FLAG_NO_PERSONALIZED_LEARNING` ;
  - application incognito ;
  - geste panique ;
  - `kill -9` pendant une écriture ;
  - pression mémoire (`onTrimMemory`) ;
  - appareil verrouillé ;
  - **logcat filtré après une saisie scriptée : aucun fragment du texte tapé**.
- **Tests de performance** : Macrobenchmark (images p95, démarrage à froid) et benchmark JVM du correcteur. Mémoire PSS ≤ 80 Mo.
- **Analyse statique** : Android Lint strict, detekt, règle maison sur les journaux, Semgrep, CodeQL. Clippy (pedantic) seulement si du Rust est introduit plus tard.
- **CI** : vérification du manifeste fusionné, deux builds reproductibles comparés, `verification-metadata.xml`, SBOM.

---

## 10. Arborescence cible (indicative)

```
app/src/main/java/com/example/app_clavier/
  ime/        KeyraInputService, InputLogic, RichInputConnection, Settings
  keyboard/   KeyboardLayout, KeyboardView, KeyDetector, PointerTracker, KeyRenderer,
              SuggestionStripView, PinPad, feedback/{Haptics,Sound}
  engine/     BinaryDictionary, SpatialModel, Suggest, NextWord
  security/   SecurityPolicy, LearningGate, SecretDetector, IncognitoApps, Panic
  storage/    KeyManager, EncryptedStore, Journal, Migration11to12,
              UserWords, Bigrams, TouchModel, ClipboardVault, Snippets
  panels/     emoji, presse-papiers, traduction, thèmes, menu (vues existantes)
  ui/         SettingsActivity, TransparencyActivity, VaultUnlockActivity, ExportActivity
buildSrc/ (ou tools/)  DictionaryCompiler, règle de lint maison
docs/        threat-model.md, architecture.md, adr/, masvs-audit.md, allowed-permissions.txt
benchmark/   Macrobenchmark et génération du Baseline Profile
```

Renommer `Mint*` en `Keyra*` est permis. Si `MintInputService` change de nom, il faut mettre à jour le manifeste, et l'utilisateur devra réactiver le clavier : préviens-le dans les notes de version.

---

## 11. Règles de travail

- **Ne suppose jamais.** Si une exigence est ambiguë, ou si deux exigences se contredisent d'une manière que le §2 ne tranche pas, signale-le et propose des options avec leurs compromis.
- **Cite les API exactes** et leur niveau d'API minimal.
- **Préfère le code simple et auditable** au code astucieux. Aucune cryptographie maison : seulement `javax.crypto`, Android Keystore et, pour Argon2id, une bibliothèque éprouvée.
- **Si une fonctionnalité ne peut pas être sécurisée correctement**, dis-le et propose une alternative plutôt que de l'implémenter mal.
- **Pas de théâtre de sécurité.** Chaque indicateur affiché à l'utilisateur doit refléter une vérification réelle.
- **Données de test synthétiques uniquement.** Ne mets jamais de vrai texte personnel dans les tests, les fixtures ou les traces.
- **Une section « Considérations de sécurité » par fichier créé ou fortement modifié**, dans le rapport de fin de phase. Pas d'en-tête de commentaire géant dans le code.

---

## 12. Pour commencer

Commence par la **phase 0 uniquement**. Livre :

1. `docs/threat-model.md` ;
2. `docs/architecture.md` ;
3. les ADR D1 à D12 et ceux des dépendances ;
4. l'arborescence cible avec le plan de migration ;
5. `PERF.md` avec les mesures de référence.

Puis **arrête-toi** et attends ma validation. Si un arbitrage du §2 te semble mauvais après lecture du code, conteste-le dans l'ADR correspondant.
