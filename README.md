# Keyra 11.0 — clavier Android AZERTY

Keyra est un clavier Android local, conçu pour le Pixel 9a et GrapheneOS. Son interface s’inspire des captures fournies par l’utilisateur. Le code de l’application est sous licence MIT (`LICENSE`) ; les dictionnaires et données Unicode gardent leurs licences respectives (`app/src/main/assets/licenses`).

## Installer et activer

1. Installe l’APK `Keyra-Pixel-9a-v11.apk` sur le téléphone.
2. Ouvre Keyra et touche **Activer le clavier** pour l’autoriser dans Android.
3. Touche **Choisir le clavier**, puis sélectionne Keyra.
4. Essaie la saisie dans le champ de démonstration.

L’APK fourni est signé avec une clé de développement. Pour une publication durable, il faut le signer avec une clé de publication propre. Le code a été testé sur l’AVD Pixel 9a ; le Pixel 9a physique sous GrapheneOS reste à valider.

## Fonctions

- AZERTY en minuscules par défaut, majuscules temporaires ou verrouillées, appui long pour accents et chiffres de la première rangée.
- Deux pages de symboles, pavé numérique, touche emoji séparée, suppression répétée et prise en charge des appuis de plusieurs doigts.
- Panneau emoji agrandi avec catégories, recherche française, 3 944 séquences Unicode et jusqu’à 18 emoji récents classés localement.
- Correction et suggestions françaises hors ligne : distance d’édition, fréquence, mots personnels et tolérance réglable. La correction s’applique à l’espace ; Retour arrière restaure le mot d’origine. Les propositions sélectionnées insèrent un espace final.
- Traduction hors ligne français ↔ anglais : sélectionne du texte ou place le curseur après une phrase, ouvre la troisième page des fonctions, touche **Traduction hors ligne**, puis **Remplacer**. La traduction est calculée localement et remplace le texte source seulement s’il n’a pas changé entre-temps.
- Thèmes colorés, couleurs dynamiques Android, deux couleurs personnalisables et image de fond avec flou réglable séparément pour le fond et les touches.
- Historique local des 20 dernières copies textuelles observées pendant l’affichage du clavier, insertion d’un toucher et effacement dans les réglages.
- Retour haptique désactivable, modes une main, réglage de hauteur, page Confidentialité et notes de version.

Le traducteur embarqué est un lexique léger de mots et d’expressions courantes. Il fonctionne sans téléchargement ni connexion, mais ne couvre pas la grammaire ni la diversité d’un grand modèle de traduction. Les mots inconnus restent inchangés. Le correcteur traite principalement l’orthographe ; sa couverture et ses prédictions ne sont pas équivalentes à celles de Gboard.

## Confidentialité

Keyra ne déclare pas de permission Internet, ne crée pas de compte et ne transmet pas le texte saisi. Les préférences, mots appris, emoji récents et copies sont dans les données privées de l’application. La sauvegarde Android de ces données est désactivée. Les champs de mot de passe et les copies marquées sensibles par l’application source sont exclus de l’historique et des suggestions. L’historique n’est pas chiffré séparément du stockage privé de l’application : il faut verrouiller le téléphone pour protéger les données locales.

Le dictionnaire français vient de [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0) et les noms français des emoji des [annotations Unicode CLDR](https://github.com/unicode-org/cldr-json). Les notices sont dans `app/src/main/assets/licenses`.

## Compiler et vérifier

[![CI](https://github.com/0x80070006/keyra/actions/workflows/ci.yml/badge.svg)](https://github.com/0x80070006/keyra/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/0x80070006/keyra/badge)](https://securityscorecards.dev/viewer/?uri=github.com/0x80070006/keyra)

Keyra est écrit en **Kotlin** (service de saisie, interface) et en **Rust** (cœur pur dans `rust/`, relié par un pont JNI minimal : voir `docs/adr/0021`). Outils attendus, tous épinglés :

| Outil | Version | Où elle est fixée |
|-------|---------|-------------------|
| JDK | 25 (JetBrains Runtime d’Android Studio ou Temurin) | `gradle/gradle-daemon-jvm.properties` ; aucun téléchargement automatique |
| SDK Android | compileSdk 37, minSdk 29 | `app/build.gradle.kts` |
| NDK | 30.0.16248370 | `ndkVersion` ; installer avec `sdkmanager "ndk;30.0.16248370"` |
| Rust | 1.99.0 et cibles `aarch64-linux-android`, `x86_64-linux-android` | `rust/rust-toolchain.toml` ; installé par `rustup` |
| Windows seulement | Visual Studio Build Tools, charge de travail C++ | pour `cargo test` sur le poste |

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:logGuard :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:sbom
cd rust; cargo fmt --all --check; cargo clippy --workspace --all-targets -- -D warnings; cargo test --workspace
```

- APK : `app/build/outputs/apk/`.
- Tests sur émulateur ou téléphone : `:app:connectedDebugAndroidTest`.
- Mesures de performance : `PERF.md`.
- Sécurité : `docs/threat-model.md`, `docs/adr/` et les rapports de phase dans `docs/`.

La CI GitHub Actions vérifie à chaque envoi :
- le formatage, clippy en mode strict, les tests et `cargo-deny` ;
- les tests JVM et instrumentés, et Lint strict ;
- l’absence de texte tapé dans les journaux (`logGuard`) ;
- le manifeste : aucune permission, et des composants en liste blanche ;
- un build release reproductible (deux builds comparés octet par octet) ;
- CodeQL et Semgrep, plus un fuzzing nocturne et OpenSSF Scorecard.
