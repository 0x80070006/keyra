# Mesures de performance — Keyra

> Chaque phase ajoute une colonne. Les cibles viennent du §6 de `PROMPT_FUSIONNE.md`.
> **Phase 0 (2026-10-06)** : Keyra 11.0 instrumenté (commit `5ef5ea6` et suivants).
> Machine : émulateur AVD « Pixel_9a », Android 17, x86_64, rendu logiciel SwiftShader. Tests JVM sur l'hôte Windows.

## 1. Tableau de bord

| Mesure | Cible 12.0 | Phase 0 | Où | Fiabilité |
|--------|-----------|---------|----|-----------|
| **Zones mortes, rangées de lettres** (repère 684, pleine largeur, y 95 à 391) | 0 % | **26,7 %** | émulateur, géométrie réelle des vues | exacte (ne dépend pas de l'appareil) |
| Zones mortes, quatre rangées (y 95 à 497) | 0 % | **27,2 %** | idem | exacte |
| Appuis au milieu des écarts de la rangée AZERTY qui tapent un caractère | 9 sur 9 | **0 sur 9** | idem, `MotionEvent` réels | exacte |
| `rebuild()` complets en tapant « Bonjour Maman » (avec Maj) | 0 | **4** (2 par appui sur Maj) | émulateur | exacte |
| Correction juste, tolérance 55 (200 fautes) | > 79,5 % | **79,5 %** (159 / 200) | JVM | exacte |
| Correction fausse, tolérance 55 | → 0 | **4,0 %** | JVM | exacte |
| Attendu dans le top 3 des suggestions | → 100 % | **96,5 %** | JVM | exacte |
| Sur-correction de mots valides hors dictionnaire, tolérance 55 / 70 | 0 % | **4,5 % / 30,3 %** | JVM | exacte |
| Coût par frappe du correcteur (candidates + correction), p50 / p95 | p95 ≤ 3 ms sur Pixel 9a | **8,0 / 32,9 ms** (max 70) | émulateur, APK debug | indicative |
| Idem | — | 0,31 / 1,92 ms (max 28,6) | JVM hôte | relative uniquement |
| Chargement du dictionnaire | ≤ 150 ms (démarrage complet) | **9 958 ms** | émulateur, APK debug | indicative, très au-dessus de la cible |
| Idem | — | 346 ms | JVM hôte | relative |
| Mémoire PSS du processus clavier pendant la frappe | ≤ 80 Mo | **113,7 Mo** (tas Java 40,5 Mo, tas natif 49,3 Mo) | émulateur, variante benchmark | indicative |
| Appui → touche dessinée, p95 | ≤ 8,3 ms | à mesurer sur Pixel 9a (`InputLatency`, réglages) | — | — |
| Appui → envoi au champ, p95 | ≤ 2 ms de traitement | à mesurer sur Pixel 9a | — | — |
| Durée CPU des images pendant la frappe, p50 / p95 (Macrobenchmark) | p95 ≤ 8,3 ms | 29 / 91 ms (sans compilation) ; 41 / 96 ms (compilation complète) | émulateur, SwiftShader | **non représentative** |
| APK release non signé (sans R8) | — | 8,6 Mo, dont 21,8 Mo de dex non compressé | build | exacte |

## 2. Ce que disent ces chiffres

1. **Les zones mortes sont réelles et mesurables** : un peu plus d'un quart de la surface ne tape rien. C'est le gain le plus simple et le plus visible de la phase 2. Le `KeyDetector` peut être branché derrière les vues actuelles avant même le nouveau rendu.
2. **Le correcteur est trop lent et trop gourmand** sur l'appareil : 33 ms au p95 par frappe et un chargement de près de 10 s sur l'émulateur, en debug. Même si le Pixel 9a en release est nettement plus rapide, l'écart avec la cible (3 ms, 150 ms) justifie le dictionnaire binaire mappé de la phase 5.
3. **La correction des lettres oubliées est faible** : 24,1 % seulement, contre environ 90 % pour les autres types de faute. La distance pondérée et le modèle spatial de la phase 5 doivent viser ce point en priorité.
4. **La tolérance 70 sur-corrige** 30 % des mots valides absents du dictionnaire (« kotlin », « tuto », « covid »…). L'auto-correction ne doit pas être plus tolérante que 55 par défaut.
5. **La mémoire dépasse la cible.** Le tas Java (40 Mo) vient surtout du trie de `HashMap` ; il disparaît avec le dictionnaire mappé.
6. **Le build embarque 21,8 Mo de dex** pour quelques milliers de lignes de code Keyra : c'est Compose et les bibliothèques AndroidX, inutilisés. Ils seront retirés en phase 1.
7. **Les images mesurées par Macrobenchmark sur émulateur ne valent rien en absolu.** Le rendu logiciel domine, et l'ordre est même inversé entre les deux modes de compilation. Le module est en place ; la vraie référence doit être prise sur le Pixel 9a.

## 3. Reproduire les mesures

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Correcteur (JVM) → app/build/reports/keyra/correction.txt
.\gradlew.bat :app:testDebugUnitTest --tests "*CorrectionQualityTest*"

# Zones mortes, reconstructions, coût du correcteur sur l'appareil → logcat, étiquette KeyraBaseline
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.app_clavier.PerformanceBaselineTest
adb logcat -d -s KeyraBaseline:I

# Fluidité de la frappe (Macrobenchmark). Sur émulateur seulement, ajouter :
#   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest
# Résultats : benchmark/build/outputs/connected_android_test_additional_output/…/benchmarkData.json

# Mémoire, clavier ouvert
adb shell dumpsys meminfo com.example.app_clavier
```

**Latence réelle sur le téléphone** : taper une centaine de caractères, puis ouvrir Réglages avancés → « Objectif de latence » et relâcher le curseur. Un message affiche les p50 et p95 des deux chemins mesurés par `InputLatency`.

**Trace Perfetto** : enregistrer avec `adb shell perfetto` (catégories `view`, `input`, `gfx`, plus les sections d'application `Keyra.*`), ou depuis l'outil de traçage système du téléphone. Les sections `Keyra.touch`, `Keyra.handleKey`, `Keyra.suggest`, `Keyra.rebuild` et `Keyra.delimiter` montrent où part le temps de chaque frappe.

## 4. À mesurer sur le Pixel 9a (GrapheneOS) avant de lancer la phase 2

Ces lignes du tableau ne peuvent pas être prises sur l'émulateur :

- [ ] `InputLatency` : appui → touche dessinée, et appui → envoi, en release.
- [ ] Macrobenchmark de frappe.
- [ ] Chargement du dictionnaire et coût par frappe, en release.
- [ ] PSS, clavier ouvert.
