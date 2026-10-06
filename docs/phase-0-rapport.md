# Rapport de la phase 0 — Conception et mesure de référence

> 2026-10-06. Aucun changement fonctionnel pour l'utilisateur, sauf le **nouveau logo** demandé en cours de phase.
> Mesures : voir `PERF.md`.

## 1. Ce qui a été fait

| Livrable | Fichier(s) |
|----------|-----------|
| Dépôt git, `.gitignore`, `.gitattributes` (fins de ligne LF pour la reproductibilité) | racine |
| Modèle de menace STRIDE | `docs/threat-model.md` |
| Diagramme d'architecture, frontières de confiance, durée de vie du texte en clair | `docs/architecture.md` |
| ADR D1 à D12 et dépendances | `docs/adr/` (20 ADR) |
| Arborescence cible et plan de migration fichier par fichier | `docs/migration-plan.md` |
| Latence mesurée depuis `MotionEvent.eventTime`, p50/p95 sans allocation | `InputLatency.kt` |
| Sections Perfetto aux noms constants | `Tracing.kt`, `MintKeyboard.kt`, `MintInputService.kt` |
| Compteur de reconstructions complètes | `MintKeyboard.rebuildCount` |
| Référence qualité et vitesse du correcteur (200 fautes, 66 mots hors dictionnaire) | `CorrectionQualityTest.kt`, `tools/generate_typos.py`, `app/src/test/resources/` |
| Mesures sur l'émulateur : reconstructions, zones mortes, coût du correcteur | `PerformanceBaselineTest.kt` |
| Module Macrobenchmark de frappe | `benchmark/`, variante `benchmark` de l'application |
| Nouveau logo (demande de l'utilisateur) | `tools/make_icon.py`, `res/drawable/ic_keyra_*.xml`, `res/mipmap-*/ic_keyra.png` |

## 2. Considérations de sécurité, fichier par fichier

**`InputLatency.kt`**
- Il enregistre des **durées seulement**, jamais de caractère, de code de touche ou de position.
- Il n'est accessible qu'à `SettingsActivity` (résumé affiché).
- Ce qui pourrait mal tourner : un futur développeur ajoute le code de touche aux échantillons « pour déboguer ». La règle de lint de la phase 1 doit couvrir ce fichier.

**`Tracing.kt`**
- `traced(name)` accepte une `String` quelconque : rien n'empêche techniquement `traced(mot)`.
- Garde-fou prévu en phase 1 : une règle de lint maison exige un littéral constant comme argument.
- Les traces ne sont lisibles que via ADB ou Perfetto avec le débogage activé, mais un rapport de bug pourrait les contenir.

**`MintKeyboard.kt` et `MintInputService.kt`**
- Modifications mécaniques : enveloppes `traced`, compteur de reconstructions, `InputLatency.down(event)`, `pressedDrawn()`.
- Aucun changement du flux de texte.
- `rebuildCount` est public : il ne contient qu'un entier.

**`app/build.gradle.kts`, variante `benchmark`, et `app/src/benchmark/AndroidManifest.xml`**
- La variante `benchmark` est signée avec la **clé de debug** et déclarée `profileable shell`.
- Elle ne doit jamais être distribuée.
- Ce qui pourrait mal tourner : publier par erreur `app-benchmark.apk`. La CI de la phase 1 vérifiera que le manifeste de **release** ne contient pas `profileable`, et que seule la variante release est signée avec la clé de publication.

**`benchmark/` (module de test)**
- Il exécute `ime enable` et `ime set` via le shell de test : il change l'IME actif de l'appareil de test.
- À ne lancer que sur un appareil de développement.

**`CorrectionQualityTest.kt`, `PerformanceBaselineTest.kt`, jeux `fr_typos.tsv` et `fr_oov_valid.tsv`**
- Données **entièrement synthétiques**, générées avec une graine fixe à partir du dictionnaire public.
- Aucun texte personnel.
- `PerformanceBaselineTest` écrit dans logcat des **mesures**, pas de texte saisi.

**`tools/generate_typos.py` et `tools/make_icon.py`**
- Scripts de développement, absents de l'APK.
- Ils n'accèdent pas au réseau et n'écrivent que dans le dépôt.

**Nouveau logo**
- Ressources statiques uniquement.
- Les anciens PNG `drawable-*dpi/ic_keyra*` ont été supprimés : aucun code ne les référençait.

**`.gitignore`**
- Exclut aussi `*.jks`, `*.keystore`, `*.apk` et `*.aab`, pour ne jamais commiter une clé de signature ou un binaire.

## 3. Écarts par rapport au prompt, et pourquoi

1. **ADR de dépendances** : un ADR par dépendance d'exécution (0013 à 0016) et un ADR groupé pour les outils de test (0017) et les dépendances futures (0020). Ces dernières auront chacune leur ADR au moment de l'ajout.
2. **Mesures sur émulateur, pas sur le Pixel 9a** : les durées absolues sont indicatives (voir `PERF.md`). Les mesures géométriques (zones mortes) et les compteurs (reconstructions) ne dépendent pas de l'appareil.
3. **Estimation corrigée** : les zones mortes étaient estimées à environ 30 % dans le prompt ; la mesure donne **26,7 %**. `PROMPT_FUSIONNE.md` a été corrigé.

## 4. Constats nouveaux de la phase 0

- **Le manifeste de release fusionné contient des composants tiers.** On y trouve `androidx.profileinstaller.ProfileInstallReceiver`, **exporté** et protégé par `DUMP`. On y trouve aussi `androidx.startup.InitializationProvider`, qui exécute `EmojiCompatInitializer` au démarrage du processus du clavier. S'y ajoute la permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Tous viennent des dépendances inutilisées. Le modèle de menace (E-1, E-3) et l'ADR-0016 ont été corrigés.
- **Le build release embarque 21,8 Mo de dex** pour un code Keyra de moins de 2 000 lignes.
- **Le chargement du dictionnaire prend environ 10 s sur l'émulateur** (APK debug), et le correcteur 33 ms au p95 par frappe.
- **Les lettres oubliées ne sont corrigées que dans 24 % des cas.**
- **À la tolérance 70, 30 % des mots valides hors dictionnaire sont sur-corrigés.**

## 5. Décisions à valider avant la phase 1

1. **ADR-0004** : passer `minSdk` à 29 (abandon d'Android 7 à 9).
2. Confirmer les arbitrages D1 (Kotlin sans Rust) et D2 (pas de Compose). Ce sont les deux plus grands écarts avec le prompt sécurité d'origine.
3. Choix de la forge pour la CI : GitHub permet OpenSSF Scorecard ; Codeberg ou une forge auto-hébergée sont possibles, sans Scorecard.
