# Prompt d'amélioration — Keyra (clavier Android AZERTY)

> À donner tel quel à un agent de code (Claude Code ou autre) ouvert dans `D:\Projet CLavier`.
> Objectif : rendre Keyra **aussi réactif et agréable que FUTO Keyboard, AnySoftKeyboard et SwiftKey**, sans perdre ses atouts (100 % hors ligne, aucune permission Internet, licence MIT, français d'abord).

---

## 0. Rôle et règles du jeu

Tu es un ingénieur Android senior spécialisé dans les méthodes de saisie (IME). Tu travailles sur **Keyra 11.0**, un clavier AZERTY français écrit en Kotlin (`app/src/main/java/com/example/app_clavier/`), ciblant le **Pixel 9a sous GrapheneOS** (minSdk 24, targetSdk 37).

Règles impératives :

1. **Hors ligne, sans exception.** Ne jamais ajouter la permission `INTERNET` ni une dépendance qui la déclare. Le test `bundledTranslationWorksWithoutNetworkPermission` doit continuer de passer.
2. **Licences.**
   - **AOSP LatinIME** (Apache 2.0) et **AnySoftKeyboard** (Apache 2.0) : tu peux t'inspirer du code et en reprendre des extraits, avec attribution dans `assets/licenses/ATTRIBUTIONS.txt` et un fichier `NOTICE`.
   - **FUTO Keyboard** est publié sous la *FUTO Source First License*, qui n'est pas une licence libre compatible MIT. Reprends ses **idées et comportements**, jamais son code. Vérifie la licence en vigueur avant toute réutilisation.
   - **SwiftKey** est propriétaire. Tu peux reproduire ses **comportements observables**, rien d'autre.
3. **Ne casse rien.** Toutes les fonctions listées dans `README.md` (emoji, presse-papiers, traduction, thèmes, image de fond, une main, champs privés…) doivent survivre. Adapte les tests existants s'ils dépendent de détails d'implémentation (par exemple, `FeatureTest` cherche les touches comme vues enfants) au lieu de les supprimer.
4. **Mesure avant et après chaque phase.** Aucune optimisation ne compte sans un chiffre. Note les mesures dans `PERF.md`.
5. **Travaille par petites étapes.** Le dossier n'est pas encore un dépôt git : lance d'abord `git init`, puis fais un commit initial et **un commit par sous-étape**. Après chaque étape, lance `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
6. **Style.** Le code actuel est très compact (plusieurs instructions par ligne). Le nouveau code doit être découpé en classes lisibles avec une responsabilité chacune. Pas de refonte cosmétique des fichiers que tu ne touches pas.

---

## 1. Diagnostic de l'existant (ce que l'analyse a trouvé)

### 1.1 Architecture actuelle

- `MintKeyboard.kt` (691 lignes) est un `ViewGroup` où **chaque touche est une `View` enfant** (`Key`). Les positions sont définies dans un repère de 684 px et mises à l'échelle dans `onMeasure` et `onLayout`.
- `MintInputService.kt` est le `InputMethodService`. Il traduit les codes texte (`"suggest:…"`, `"replaceLong:…"`, `"delete"`…) en appels à `InputConnection`.
- `FrenchCorrector.kt` : trie de `HashMap<Char,Node>` construit au démarrage depuis `fr_frequency.txt` (50 000 lignes, FrequencyWords/OpenSubtitles), distance de Damerau-Levenshtein bornée, classement par fréquence.
- `UserLexicon.kt` : mots appris, stockés en JSON dans les SharedPreferences `keyboard`.

### 1.2 Problèmes qui nuisent à la réactivité, classés par gravité

| # | Problème | Où | Effet ressenti |
|---|----------|----|----------------|
| R1 | **Zones mortes entre les touches.** Les touches font 58 × 85 sur un pas de 68 × ~105 (repère 684). Un appui dans l'écart ne touche aucune `View` et `MintKeyboard.onTouchEvent` le consomme sans rien taper. Cela représente **environ 30 % de la surface des rangées de lettres**. | `MintKeyboard.kt:323-326`, `:136-139` | Frappes perdues, surtout en tapant vite ou avec le pouce. Cause n° 1 de la sensation de clavier « qui rate ». |
| R2 | **`rebuild()` détruit et recrée toutes les vues** (`removeAllViews`) à chaque Maj, à chaque retour en minuscule après une majuscule, à chaque changement de mode, et 2 à 3 fois à chaque affichage (`onStartInput` → `configure` → `reset`, puis `onStartInputView`, puis `onWindowShown` → `refreshTheme`). | `MintKeyboard.kt:285-302`, `:248`, `:266` ; `MintInputService.kt:56-58` | Saccades sur Maj et à l'ouverture, ramasse-miettes (GC) en pleine frappe. |
| R3 | **Allers-retours IPC sur le thread principal à chaque frappe.** `getTextBeforeCursor` et `getSelectedText` sont appelés de façon synchrone dans `calculateSuggestions`, `delimiter` (3 appels par espace), `undoCorrection` (à chaque Retour arrière) et `suggest:`. | `MintInputService.kt:87`, `:107`, `:115`, `:121`, `:147`, `:204` | Latence variable selon l'application cible (Chrome et WebView sont lents à répondre). |
| R4 | **Correction asynchrone après coup.** Si la suggestion n'est pas prête au moment de l'espace, le mot fautif est écrit, puis remplacé plus tard. | `MintInputService.kt:126-143` | Le texte « saute » sous les yeux. Risque de course si l'utilisateur continue de taper. |
| R5 | **Bandeau de suggestions en retard.** Il y a 85 ms d'anti-rebond dans le service, plus jusqu'à 90 ms de garde dans la vue, plus le calcul. Le bandeau est mis à jour en retirant puis réajoutant des vues, ce qui relance un `requestLayout` complet. | `MintInputService.kt:81` ; `MintKeyboard.kt:76-96` | Suggestions décalées d'environ 200 ms. Elles changent pendant que le doigt vise la suggestion. |
| R6 | **Correcteur trop coûteux.** `candidates()` est appelé deux fois par frappe (directement, puis via `correction()`). `fold()` recompile une `Regex` et normalise en NFD à chaque appel, y compris dans la boucle chaude (`fold(w)` pour chaque candidat). Chaque nœud visité alloue un `IntArray`. | `FrenchCorrector.kt:99-101`, `:62`, `:132`, `:50` | Charge CPU et GC inutiles. Le test tolère 150 ms par recherche, ce qui est 30 fois trop. |
| R7 | **Démarrage lourd.** 50 000 mots sont lus en texte et un trie de `HashMap` est bâti à chaque création du service. | `MintInputService.kt:46-51` | Pas de suggestions pendant les premières secondes. Mémoire élevée. |
| R8 | **Apprentissage qui réécrit le fichier de réglages.** `UserLexicon.record` réécrit tout le JSON (jusqu'à 2 000 mots) dans le fichier `keyboard`, celui des réglages. Chaque écriture réveille le `prefsListener`. `personal()` relit et redécoupe la liste des mots personnels à chaque espace. | `UserLexicon.kt:10-23` ; `MintInputService.kt:75` | Écritures disque fréquentes et travail inutile sur le thread principal. |
| R9 | **Mesure de latence biaisée.** `InputLatency.down()` utilise `uptimeMillis()` au moment du traitement au lieu de `event.eventTime`. Il ignore donc le délai de distribution de l'événement. | `InputLatency.kt` ; `MintKeyboard.kt:194` | Les chiffres affichés sont trop optimistes. |
| R10 | **Build non optimisé.** R8 est désactivé en release, il n'y a pas de Baseline Profile, et Compose est embarqué sans être utilisé (toutes les activités sont des `Activity` classiques). | `app/build.gradle.kts` | Code interprété ou JIT au premier usage, APK plus lourd. |
| R11 | **Allocations dans les chemins chauds.** Chaque `Key` crée un `setOf(...)` et un `Typeface`. `onDraw` alloue un tableau `vararg` par icône. La préférence `haptic` est relue à chaque appui. | `MintKeyboard.kt:628-629`, `:675`, `:194` | Petite pression GC continue. |

### 1.3 Problèmes qui nuisent à l'agrément

| # | Problème | Où |
|---|----------|----|
| A1 | **Pas de majuscule automatique** en début de phrase ou de champ. `EditorInfo.initialCapsMode` et `getCursorCapsMode` sont ignorés. | absent |
| A2 | **Typographie française inversée.** Le code supprime l'espace avant `! ? ; :`, alors qu'en français on écrit « Bonjour ! » avec une espace insécable. | `MintInputService.kt:111` |
| A3 | **On ne sait pas ce que fera l'espace.** Aucune suggestion n'est mise en évidence comme correction automatique, le mot tapé tel quel n'est pas proposé, et la meilleure suggestion n'est pas au centre. | `MintKeyboard.kt:275-276` |
| A4 | **Le mot restauré n'est pas appris.** Après une annulation de correction par Retour arrière, le mot d'origine n'est pas mémorisé ; il sera de nouveau corrigé la fois suivante. | `MintInputService.kt:145-149` |
| A5 | **Prédiction du mot suivant figée.** Elle repose sur une table codée en dur de 23 entrées, sans apprentissage des paires de mots de l'utilisateur. | `FrenchCorrector.kt:122-131` |
| A6 | **Correction aveugle au clavier.** La distance d'édition est uniforme : `azerty → zzerty` coûte autant que `azerty → mzerty`. Les coordonnées du toucher ne sont pas utilisées. | `FrenchCorrector.kt:53` |
| A7 | **Validation à l'appui (`ACTION_DOWN`), sans glissement possible.** Impossible de corriger une frappe en glissant le doigt. Glisser de `?123` ou de Maj vers un caractère ne fonctionne pas. Le contournement multi-doigts (`dispatchTouchEvent` sur `ACTION_POINTER_DOWN`) est fragile. | `MintKeyboard.kt:100-130`, `:194` |
| A8 | **Gestes absents.** Pas de déplacement du curseur en glissant sur Espace, pas de suppression par mot. La répétition de Retour arrière a une vitesse fixe (230 ms, puis toutes les 30 ms) sans accélération. | `MintKeyboard.kt:204-217` |
| A9 | **Retour sensoriel limité.** Seul `KEYBOARD_TAP` est utilisé : pas d'intensité réglable, pas de son, pas de bulle d'aperçu au-dessus de la touche. | `MintKeyboard.kt:115`, `:194` |
| A10 | **Réglages manquants.** Le délai d'appui long (350 ms) n'est pas réglable, il n'y a pas d'option de rangée de chiffres, et l'appui long n'ouvre pas de symboles supplémentaires. | `MintKeyboard.kt:194`, `:322` |

---

## 2. Ce qu'on emprunte à chaque référence

| Référence | Ce qu'on reprend (idées, pas code sauf Apache 2.0) |
|-----------|----------------------------------------------------|
| **FUTO Keyboard** (dérivé d'AOSP LatinIME) | Architecture LatinIME : une seule vue dessinée sur Canvas, `KeyDetector` sans zones mortes, un `PointerTracker` par doigt, `RichInputConnection` (cache local du texte), *composing text*, chaîne `Suggest` avec seuil d'auto-correction, annulation par Retour arrière. Glissement sur la barre d'espace pour déplacer le curseur, vibration réglable, barre d'actions, tout hors ligne. |
| **AnySoftKeyboard** (Apache 2.0) | Dictionnaire utilisateur et **dictionnaire « mot suivant »** appris localement (SQLite), gestes configurables sur le clavier (balayages haut, bas, gauche, droite), touches à appui long riches, délais d'appui long et de répétition réglables, son et vibration séparés, frappe gestuelle comme cible à long terme. |
| **SwiftKey** (comportements) | Bandeau à 3 suggestions avec **la meilleure au centre, en gras si l'espace va l'appliquer**, prédiction du mot suivant personnalisée, **zones de toucher dynamiques** (la touche la plus probable s'agrandit légèrement de façon invisible), modèle de toucher appris sur l'utilisateur, rangée de chiffres optionnelle, épinglage dans le presse-papiers, statistiques de frappe locales. |

---

## 3. Objectifs chiffrés (Pixel 9a, build release)

| Mesure | Cible |
|--------|-------|
| `ACTION_DOWN` (`event.eventTime`) → touche affichée enfoncée | ≤ 1 frame (≤ 8,3 ms à 120 Hz), p95 |
| `ACTION_DOWN` → retour haptique | même événement, 0 ms ajoutée |
| Traitement de la frappe → appel `commitText` ou `setComposingText` | ≤ 2 ms, p95, **0 IPC de lecture** |
| Frappe → bandeau de suggestions à jour | ≤ 40 ms, p95 |
| `FrenchCorrector.candidates()` sur mot de 6 lettres | ≤ 3 ms, p95, 0 allocation par nœud |
| Auto-correction à l'espace | **synchrone**, sans saut visible de texte |
| Allocations pendant la frappe (`onTouchEvent` + `onDraw`) | 0 objet par frappe |
| Appels à `rebuild()` complets pendant la saisie de lettres, Maj comprise | 0 |
| Service démarré → dictionnaire prêt | ≤ 150 ms |
| Taux de frappes perdues (appuis dans un écart entre touches) | 0 % |

---

## 4. Plan de travail par phases

Fais les phases **dans l'ordre**. Chaque phase se termine par des tests verts et une mesure notée dans `PERF.md`.

### Phase 0 — Instrumentation et filet de sécurité

1. `git init`, puis un commit initial avec un `.gitignore` propre (exclure `build/`, `.gradle/`, `.kotlin/`, `local.properties`).
2. `InputLatency` : mesurer depuis `MotionEvent.eventTime`. Ajouter les étapes `down→pressed-frame` (via `Choreographer.postFrameCallback`) et `down→commit`. Calculer les percentiles p50 et p95 sur une fenêtre glissante de 500 frappes, sans allocation par échantillon (tableau circulaire de `Long`).
3. Ajouter des `Trace.beginSection`/`endSection` (androidx.tracing) autour de : touche, commit, suggestions, correction, dessin. Documenter dans `PERF.md` la commande Perfetto pour capturer une trace.
4. Activer `StrictMode` (thread principal : disque et réseau) en debug uniquement dans le service.
5. Créer un module `:benchmark` (Macrobenchmark) qui ouvre `MainActivity`, met le focus sur le champ de test et tape une phrase de 200 caractères via `UiAutomator`. Il mesure `FrameTimingMetric` et sert à **générer un Baseline Profile**.
6. Ajouter des tests unitaires JVM pour le correcteur : jeu de 200 fautes réalistes (`fr_typos.tsv`) avec le taux de bonne correction attendu. Il sert de **référence de qualité** pour ne pas régresser.

### Phase 1 — Moteur de toucher et de rendu (le plus gros gain)

Remplacer « une `View` par touche » par le modèle LatinIME, utilisé par FUTO et ASK.

1. **Modèle `KeyboardLayout`** (données pures, testable sur JVM) : liste de `KeyDef(code, label, hint, popupChars, x, y, w, h, kind)` dans le repère 684. Les lignes 321 à 351 de `MintKeyboard.kt` deviennent des définitions déclaratives par mode (lettres, symboles 1 et 2, pavé numérique). La variante majuscule **ne crée pas de nouvelle disposition** : seul le libellé dessiné change.
2. **`KeyboardView`** : une seule `View` qui dessine toutes les touches sur le Canvas.
   - Garde le style actuel (coins arrondis, icônes vectorielles, textures de thème). Précalcule les `Path` d'icônes, `Paint`, `Typeface` et tailles de texte **une fois par disposition et par taille**, jamais dans `onDraw`.
   - Ne redessine que les touches modifiées (`invalidate(Rect)`), ou cache le fond du clavier dans un `Bitmap`/`RenderNode` et dessine seulement l'état enfoncé par-dessus.
   - Maj : on change un drapeau et on appelle `invalidate()`, **jamais** `rebuild()`.
3. **`KeyDetector` sans zones mortes** : chaque point du clavier appartient à la touche **la plus proche** (distance au rectangle, écarts compris). Grille de proximité précalculée, par exemple 32 × 16 cellules, chaque cellule listant ses touches candidates, pour une recherche en O(1). Test JVM : aucun point de la zone des lettres ne renvoie `null`.
4. **Zones de toucher dynamiques (SwiftKey/Gboard)**, désactivables : à partir du mot en cours, le moteur fournit la probabilité de chaque lettre suivante. Le `KeyDetector` pondère la distance par `-log(p)`, avec un plafond pour qu'une touche ne « vole » jamais plus de 25 % de sa voisine. Le dessin des touches ne change pas.
5. **`PointerTracker` par doigt** (pas de contournement dans `dispatchTouchEvent`) :
   - `DOWN` : touche enfoncée affichée, haptique et son **immédiatement**. Bulle d'aperçu optionnelle.
   - `MOVE` : si le doigt glisse sur une autre touche avant `UP`, la touche active change (glissement de correction, comme LatinIME). Si le doigt est parti d'une touche de mode (`?123`, Maj), on bascule temporairement et le relâcher sur un caractère tape ce caractère puis revient au mode précédent.
   - `UP` : validation de la touche.
   - **Second doigt posé** pendant que le premier est enfoncé : le premier est validé immédiatement (« phantom up » de LatinIME). L'ordre des lettres est conservé en tapant à deux pouces.
   - Réglage **« Valider à l'appui / au relâchement »** : par défaut au relâchement avec phantom up, comme FUTO, ASK et SwiftKey. Garde l'option « à l'appui » pour ceux qui préfèrent le comportement actuel. Mesure les deux dans `PERF.md`.
   - Stocke les coordonnées `(x, y)` de chaque lettre du mot en cours. Elles servent à la phase 3.
6. **Appui long et accents** : un délai réglable (200 à 600 ms, 300 ms par défaut) ouvre un panneau de touches secondaires (`popupChars`), dessiné par la même vue. Glisser vers un choix puis relâcher le sélectionne. Chaque touche de lettre reçoit des symboles secondaires utiles (exemple : `e` → `é è ê ë € 3`).
7. **Bandeau de suggestions** dessiné dans la même vue, ou dans une petite vue dédiée qui ne fait jamais `requestLayout` : remplacer ses textes suffit.
8. **Accessibilité** : `ExploreByTouchHelper` expose chaque touche avec son `contentDescription` actuel (`"Espace"`, `"Majuscules"`, `"Effacer"`…). Adapte `FeatureTest.find()` pour passer par les nœuds d'accessibilité ou par une API de test `keyboardView.keyFor(label)` au lieu des vues enfants.
9. Les panneaux (emoji, menu, presse-papiers, traduction, thèmes, réglages rapides) peuvent rester en vues classiques. Ils s'affichent **au-dessus** de `KeyboardView` ou à sa place, et ne sont créés qu'à la première ouverture, puis gardés en cache.
10. Ne reconstruire qu'en cas de vrai changement : thème modifié, taille modifiée, rotation. `onWindowShown` ne doit plus appeler `refreshTheme()` si rien n'a changé (comparer une empreinte de la palette). `configure()` ne doit pas reconstruire deux fois par focus.

**Critères d'acceptation de la phase 1** :

- 0 % de frappes perdues dans les écarts (test JVM sur `KeyDetector`, test instrumenté avec des `MotionEvent` placés dans les écarts).
- `rapidPressesAreNeverDropped` adapté et vert. Nouveau test « deux doigts alternés, 200 lettres, ordre conservé ».
- 0 `rebuild()` en tapant `Bonjour Maman` avec Maj (compteur de debug).
- p95 `down→pressed-frame` ≤ 8,3 ms.

### Phase 2 — Logique de saisie (comme LatinIME / FUTO)

1. **`RichInputConnection`** : cache local du texte avant le curseur (jusqu'à 1 000 caractères), du texte sélectionné et de la position du curseur.
   - Le cache est rempli une seule fois à `onStartInputView` (une lecture IPC), puis tenu à jour par chaque commit, suppression et `onUpdateSelection`.
   - On ne relit depuis l'application que si `onUpdateSelection` signale une position différente de celle attendue (déplacement par l'utilisateur ou modification par l'application).
   - Supprime tous les `getTextBeforeCursor` et `getSelectedText` des chemins de frappe (R3).
2. **Composing text** (option, activée par défaut) : le mot en cours est envoyé via `setComposingText`. Une suggestion ou une correction se fait par `setComposingText` puis `finishComposingText`, sans `deleteSurroundingText`. Conserve un mode « sans soulignement » (commit direct) pour les applications qui gèrent mal le composing ; prévois une petite liste blanche de paquets.
3. **Majuscule automatique** (A1) : `getCursorCapsMode(inputType)` calculé **à partir du cache** (début de champ, après `. ! ?` suivi d'une espace, après un saut de ligne), en respectant `TYPE_TEXT_FLAG_CAP_SENTENCES`, `CAP_WORDS` et `CAP_CHARACTERS`. Ajoute un réglage pour la désactiver.
4. **Typographie française** (A2), réglage « Espaces typographiques françaises », activé par défaut :
   - Avant `? ! ; :` : remplacer l'espace tapée, ou en insérer une, par une espace fine insécable (U+202F). Prévoir le choix U+00A0 ou espace normale.
   - Avant `. ,` : supprimer l'espace (comportement actuel).
   - Guillemets `«` `»` avec espaces insécables, en appui long sur `"`.
   - Ne jamais appliquer ces règles dans les champs e-mail, URL ou mot de passe.
5. **Auto-correction synchrone** (R4) : à l'espace, si le calcul en cours n'est pas terminé, attendre son résultat au plus 30 ms (`Future.get(timeout)`) sur le cache. Le correcteur de la phase 3 étant sous 3 ms, l'attente est rare. Supprime le chemin « commit puis remplacement différé » (`MintInputService.kt:130-143`).
6. **Annulation et apprentissage** (A4) : Retour arrière juste après une correction restaure le mot (déjà fait). En plus, le mot restauré est **ajouté au dictionnaire personnel** et **mis sur une liste noire de correction** pour la session, comme LatinIME et FUTO.
7. **Retour arrière** (A8) : répétition qui accélère (délai initial 350 ms, puis de 80 ms à 25 ms). Après environ 1,5 s de maintien, suppression **mot par mot**. Geste : glisser vers la gauche depuis Retour arrière sélectionne des mots à effacer, et relâcher les supprime (Gboard/SwiftKey).
8. **Barre d'espace = pavé tactile** (FUTO/SwiftKey) : un glissement horizontal au-delà d'un seuil de 12 dp déplace le curseur, un caractère par pas de ~10 dp, avec accélération. Il annule la frappe de l'espace. L'appui long sur Espace garde le sélecteur de clavier.
9. **Gestes clavier configurables (ASK)** : balayage vers le bas = masquer le clavier ; vers le haut = Maj ; vers la gauche sur les lettres = supprimer le mot (désactivé par défaut pour éviter les faux positifs).
10. **Double espace = point** : ajouter une fenêtre de temps (≤ 600 ms entre les deux espaces), comme LatinIME.
11. Les préférences lues pendant la frappe (`haptic`, `correction`, `tolerance`, mots personnels…) sont mises en cache dans un objet `Settings` immuable. Il est reconstruit uniquement dans le `OnSharedPreferenceChangeListener`. Les mots personnels deviennent un `HashSet` (R8, R11).

### Phase 3 — Moteur de prédiction et de correction

1. **Dictionnaire binaire précompilé** (R6, R7) :
   - Une tâche Gradle (ou un script Kotlin dans `buildSrc`) transforme `fr_frequency.txt` en `fr_main.dict` au moment du build.
   - Format : trie compact (double-array ou LOUDS) stocké dans un `ByteBuffer`. Il contient la forme repliée (sans accents), une référence vers la forme affichée et une fréquence quantifiée sur 8 bits (échelle logarithmique, comme LatinIME).
   - Le fichier n'est pas compressé (`androidResources { noCompress += "dict" }`) et il est chargé par `AssetFileDescriptor` + `FileChannel.map` : **aucune construction au démarrage**.
   - Les formes repliées sont précalculées : plus aucun `fold()` ni `Regex` dans la recherche.
2. **Recherche sans allocation** : réutiliser une matrice de lignes de Levenshtein préallouée (profondeur maximale × longueur maximale) et parcourir le trie en profondeur avec élagage. **Un seul appel par frappe** produit à la fois les candidats et la décision de correction.
3. **Distance pondérée par le clavier** (A6) :
   - Substitution entre touches voisines AZERTY : coût 0,5. Touches éloignées : 1,0.
   - Omission ou ajout d'une lettre voisine de la précédente (frappe doublée) : coût réduit.
   - Accent manquant (`e` / `é`) : coût 0,1.
   - Apostrophe manquante (`jarrive` → `j'arrive`) : coût 0,2.
   - Si les coordonnées de toucher de la phase 1 sont disponibles, on peut remplacer le coût de substitution par `-log P(touche | x, y)` avec une gaussienne centrée sur chaque touche (modèle spatial de LatinIME et FUTO).
4. **Modèle de toucher personnel (SwiftKey)** : pour chaque touche, mémoriser une moyenne mobile du décalage `(dx, dy)` entre le point touché et le centre de la touche, mais **seulement pour les mots validés sans correction**. Cette moyenne recentre la gaussienne. Les données restent locales et peuvent être effacées depuis les réglages.
5. **Score de correction** : `score = coût_édition_pondéré − α·log(fréquence) − β·log(P_bigramme(mot | mot_précédent)) − γ·bonus_personnel`. Garder le comportement de la tolérance actuelle (0 à 100) en la reliant à un **seuil de confiance unique** (écart de score avec le deuxième candidat), comme `autocorrect_threshold` de LatinIME. Le jeu `fr_typos.tsv` de la phase 0 doit donner un meilleur taux qu'avant, avec 0 correction de mot valide.
6. **Prédiction du mot suivant** (A5) :
   - **Bigrammes embarqués** : générer `fr_bigrams.dict` (environ 100 000 paires les plus fréquentes) à partir d'un corpus libre de licence compatible, comme le corpus OpenSubtitles déjà utilisé par FrequencyWords. Documenter la licence dans `ATTRIBUTIONS.txt`.
   - **Bigrammes et trigrammes appris (ASK, SwiftKey)** : dans une base SQLite locale (`user_history.db`), compter les paires de mots que l'utilisateur valide. Appliquer un vieillissement exponentiel et plafonner à environ 20 000 entrées. Rien n'est appris dans les champs privés ni dans les champs avec `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
   - Remplace `nextWordMap` (`FrenchCorrector.kt:122`).
7. **Dictionnaire personnel** : migrer `UserLexicon` et les « mots personnels » vers la même base SQLite (table `user_words`), avec une migration automatique des données JSON existantes. Les écritures sont regroupées en arrière-plan (au plus une toutes les 2 s, plus une à `onFinishInput`). Les réglages restent dans le fichier de préférences `keyboard`, seuls (R8).
8. **Bandeau à 3 suggestions façon SwiftKey** (A3) :
   - **Centre** : le meilleur candidat, en **gras** si l'espace va l'appliquer.
   - **Gauche** : le mot tapé tel quel, entre guillemets quand une correction est prévue. Le toucher l'insère sans correction et l'apprend.
   - **Droite** : la deuxième suggestion.
   - Appui long sur une suggestion : « Ne plus proposer » (liste noire locale).
   - Le bandeau ne change pas pendant qu'un doigt est posé dessus (garde l'idée actuelle de `renderSuggestions`, mais limitée à la zone du bandeau, sans bloquer tout le clavier).
   - Anti-rebond ramené à 0 ms quand le calcul prend moins de 3 ms. Le calcul passe par un exécuteur unique qui **abandonne les requêtes périmées**.
9. **Suggestions d'emoji (SwiftKey)** : quand le mot en cours correspond exactement à une annotation CLDR fréquente (« cœur », « merci », « chat »…), proposer l'emoji en 3ᵉ position, avec un réglage pour le désactiver.

### Phase 4 — Agrément et finitions

1. **Haptique réglable (FUTO/ASK)** :
   - Mode « système » : `KEYBOARD_PRESS` à l'appui, `KEYBOARD_RELEASE` optionnel au relâchement.
   - Mode « personnalisé » : `VibrationEffect.Composition` avec `PRIMITIVE_TICK` ou `PRIMITIVE_CLICK` et une intensité de 0 à 1 (API 30+), ou `createOneShot(durée, amplitude)` sur les versions plus anciennes.
   - Le réglage est lu dans le cache `Settings`.
2. **Son de touche** optionnel : `AudioManager.playSoundEffect` avec `FX_KEYPRESS_STANDARD`, `FX_KEYPRESS_DELETE`, `FX_KEYPRESS_SPACEBAR` et `FX_KEYPRESS_RETURN`, volume réglable.
3. **Bulle d'aperçu** au-dessus de la touche (option, désactivée par défaut, comme Gboard) : elle est dessinée dans une `PopupWindow` préallouée réutilisée, jamais recréée.
4. **Rangée de chiffres optionnelle (SwiftKey, ASK)**. Quand elle est affichée, les indices de chiffres sur la première rangée sont masqués.
5. **Presse-papiers** : épingler un élément (il n'expire pas), supprimer un élément par appui long. La capture se fait hors du thread principal (sérialisation JSON sur le worker).
6. **Réglages « Saisie »** regroupés : validation à l'appui ou au relâchement, délai d'appui long, zones dynamiques, haptique et intensité, son, bulle d'aperçu, rangée de chiffres, majuscule auto, espaces typographiques, gestes, pavé tactile Espace, composing.
7. **Statistiques locales (SwiftKey)**, facultatif : nombre de frappes, corrections appliquées et annulées, latence p95. Rien n'est envoyé, et un bouton les efface.
8. **Frappe gestuelle (FUTO, ASK, SwiftKey Flow)** : **hors périmètre de ce lot**. Prépare seulement l'architecture : le `PointerTracker` enregistre la trajectoire, et le moteur accepte une suite de points en entrée. Elle fera l'objet d'un prompt séparé.

### Phase 5 — Build et démarrage

1. Activer R8 en release (`isMinifyEnabled = true`, `isShrinkResources = true`) avec des règles de conservation pour le service IME, les activités déclarées dans le manifeste et `method.xml`. Vérifier `rules.keep`.
2. **Baseline Profile** généré par le module `:benchmark` (phase 0), avec `androidx.profileinstaller` : le code de frappe est compilé à l'avance dès l'installation, sans période de chauffe JIT.
3. Retirer Compose et Material 3 si aucune activité ne les utilise, ou migrer `MainActivity` et `SettingsActivity` vers Compose, mais **ne jamais** l'utiliser dans la vue du clavier.
4. Charger le dictionnaire mappé et la base utilisateur **en parallèle** dans `onCreate` du service. `EmojiCatalog` ne se charge qu'à la première ouverture du panneau emoji.
5. Vérifier sur Android 15+ (bord à bord forcé) que la vue gère bien les insets de la barre de navigation gestuelle (`WindowInsets` côté IME) et que les touches du bas ne sont pas sous la barre.

---

## 5. Architecture cible (indicative)

```
app/src/main/java/com/example/app_clavier/
  ime/
    KeyraInputService.kt        // anciennement MintInputService, orchestration seulement
    InputLogic.kt               // règles de saisie : délimiteurs, majuscule auto, typographie FR, annulation
    RichInputConnection.kt      // cache local du texte et composing
    Settings.kt                 // instantané immuable des préférences
  keyboard/
    KeyboardLayout.kt           // définitions déclaratives AZERTY, symboles, pavé numérique
    KeyboardView.kt             // une seule vue Canvas et ExploreByTouchHelper
    KeyDetector.kt              // touche la plus proche, grille de proximité, zones dynamiques
    PointerTracker.kt           // un par doigt : glissement, phantom up, appui long
    KeyRenderer.kt              // dessin des touches et icônes précalculées
    SuggestionStripView.kt
    feedback/Haptics.kt, Sound.kt
  engine/
    BinaryDictionary.kt         // trie mappé en mémoire
    SpatialModel.kt             // voisinage AZERTY, gaussiennes, modèle personnel
    Suggest.kt                  // candidats, score, décision de correction
    UserHistory.kt              // SQLite : mots, bigrammes, liste noire
  panels/                       // emoji, presse-papiers, traduction, thèmes, menu (vues existantes)
buildSrc/ ou tools/
  DictionaryCompiler.kt         // fr_frequency.txt → fr_main.dict et fr_bigrams.dict
```

Renommer les classes `Mint*` en `Keyra*` est optionnel. Si tu le fais, mets à jour `AndroidManifest.xml` et prévois que les utilisateurs devront peut-être réactiver le clavier.

---

## 6. Plan de tests

- **JVM** :
  - `KeyDetectorTest` : couverture totale de la zone, sans zone morte ; voisin le plus proche ; plafond des zones dynamiques.
  - `SuggestTest` : jeu `fr_typos.tsv`, aucune correction de mot valide, apostrophes, accents, majuscules conservées.
  - `InputLogicTest` : majuscule auto, typographie française, double espace avec fenêtre de temps, annulation suivie d'apprentissage. Utiliser une fausse `InputConnection`.
  - `DictionaryCompilerTest` : aller-retour texte → binaire → recherche.
  - Benchmark JVM : `candidates()` p95 < 3 ms sur 10 000 requêtes.
- **Instrumentés** :
  - `FeatureTest` adapté.
  - Appuis dans les écarts entre touches.
  - Deux pouces alternés (200 lettres, ordre conservé).
  - Glissement vers une autre touche avant relâchement.
  - Glissement depuis `?123`.
  - Pavé tactile sur Espace.
  - Retour arrière qui accélère puis passe en mode mot.
  - Champ mot de passe : ni suggestions, ni apprentissage, ni presse-papiers.
- **Macrobenchmark** : saisie de 200 caractères, avec `FrameTimingMetric` p95 < 8,3 ms et 0 frame saccadée sur Maj.
- **Manuel, sur Pixel 9a GrapheneOS** : Chrome, Signal, Molly, application SMS, barre de recherche, champ e-mail, champ numérique. Noter le ressenti et les chiffres de `InputLatency` dans `PERF.md`.

---

## 7. Livrables attendus

1. Le code, phase par phase, avec un commit par sous-étape et des messages en français.
2. `PERF.md` : mesures avant et après chaque phase (tableau du §3), avec la méthode utilisée.
3. `README.md` et les notes de version (panneau `patchnotes`, version 12.0) mis à jour.
4. `ATTRIBUTIONS.txt` et `NOTICE` complétés pour tout extrait Apache 2.0 et pour tout nouveau corpus.
5. Un court rapport final : ce qui a été fait, ce qui reste (frappe gestuelle, autres langues), les risques connus.

**Commence par la phase 0, puis la phase 1.** Avant d'écrire du code, présente en quelques lignes ton plan détaillé pour la phase 1 (classes, ordre de migration, adaptation des tests). Si un choix d'architecture du présent document te semble mauvais après lecture du code, dis-le et propose mieux plutôt que de l'appliquer aveuglément.
