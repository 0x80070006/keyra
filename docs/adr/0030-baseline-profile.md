# ADR-0030 — Baseline Profile et androidx.profileinstaller

- **Statut** : accepté (phase 7).

## Contexte
Sans profil, ART interprète puis compile à la volée le code du clavier aux premières frappes après une installation ou une mise à jour. Le Play Store installe le profil lui-même, mais pas F-Droid ni l'installation depuis GitHub.

## Décision
- **Profil** : `app/src/main/baseline-prof.txt`, généré par `benchmark/…/BaselineProfileGenerator`. Le générateur ouvre le clavier, tape un texte synthétique (« bonjuor ca va bien merci », avec une correction), passe aux symboles et revient.
  - Le build `benchmark` est compilé sans renommage ni optimisation R8 (`app/src/benchmark/keepRules/rules.keep`) pour que le profil porte les vrais noms.
  - R8 réécrit ensuite le profil selon le renommage du build de release.
  - Le profil ne contient que des noms de classes et de méthodes, aucun texte.
- **Dépendance d'exécution** : `androidx.profileinstaller:profileinstaller:1.4.1` (Apache-2.0). Elle installe le profil au premier lancement, quelle que soit la source de l'APK. Elle entraîne `androidx.startup`, `androidx.concurrent:concurrent-futures` et `androidx.tracing`, toutes Apache-2.0 et vérifiées par `verification-metadata.xml`.
- **Composants ajoutés au manifeste**, en liste blanche :
  - `provider androidx.startup.InitializationProvider` : non exporté ;
  - `receiver androidx.profileinstaller.ProfileInstallReceiver` : exporté, mais protégé par `android.permission.DUMP`, que seuls le shell et le système détiennent. Il ne reçoit que des commandes d'installation du profil, sans donnée.
- **Aucun accès réseau**, aucune permission ajoutée.

## Vérification
- L'APK contient `assets/dexopt/baseline.prof` et `baseline.profm`.
- Deux builds de release propres consécutifs donnent le même APK (SHA-256 `645a3934…` au moment de l'ADR). Le job `reproducible` de la CI le vérifie à chaque envoi.
- Le gain à froid se mesure avec `TypingBenchmark` (`CompilationMode.Partial`) sur le Pixel 9a. Ce n'est **pas encore mesuré** : l'utilisateur a demandé de ne pas mesurer sur le téléphone pour l'instant, et l'émulateur ne donne pas de chiffre fiable.
