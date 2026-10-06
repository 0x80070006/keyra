# Keyra 12.0 — clavier Android français, rapide et privé

Keyra est un clavier Android local, conçu pour le Pixel 9a et GrapheneOS. Il est écrit en Kotlin, avec un cœur en Rust. Il n'a **aucun accès réseau**, et tout ce qu'il garde est **chiffré**. Le code est sous licence MIT (`LICENSE`) ; les dictionnaires et données Unicode gardent leurs licences (`app/src/main/assets/licenses`).

## Installer
1. **Télécharger** : prends l'APK de la [dernière publication](https://github.com/0x80070006/keyra/releases). Vérifie-le si tu le souhaites : `docs/release.md`, avec `cosign` et `apksigner`.
2. **Activer** : ouvre Keyra et touche **Activer le clavier**, puis **Choisir le clavier**.
3. **Essayer** : écris dans le champ de démonstration.

## Fonctions
- **Frappe réactive**, inspirée de FUTO Keyboard, AnySoftKeyboard et SwiftKey :
  - la touche visée est la plus proche du doigt, sans zone morte ;
  - validation au relâchement ;
  - un second doigt valide aussitôt le premier ;
  - glisser depuis ?123 vers un symbole ;
  - barre d'espace en pavé tactile ;
  - Retour arrière qui accélère, puis efface mot par mot ;
  - gestes.
- **Correction et prédiction françaises hors ligne** (moteur Rust) :
  - distance d'édition pondérée par la géométrie AZERTY ;
  - bandeau « mot tapé | **correction** | suivante » ;
  - mot suivant appris sur l'appareil ;
  - Retour arrière après une correction rend le mot tapé.
- **Dispositions** : AZERTY, BÉPO, QWERTY, QWERTZ, Dvorak ; rangée de chiffres en option.
- **Fonctions pratiques** :
  - extraits de texte (« adr » → ton adresse), protégeables par empreinte ;
  - emoji avec recherche française ;
  - presse-papiers éphémère avec épinglage ;
  - traduction FR ↔ EN légère et locale.
- **Apparence** : thèmes, couleurs du téléphone, image de fond floutée ; vibration, son et bulle d'aperçu réglables.

## Vie privée et sécurité
- **Réseau** : pas de permission `INTERNET`. Le **tableau de transparence** (Réglages) affiche deux preuves système (`PackageManager`, `TrafficStats`), puis liste chaque donnée conservée, à chercher, modifier ou supprimer.
- **Chiffrement** :
  - mots appris, paires, mots personnels, emoji, presse-papiers, extraits et choix incognito : XChaCha20-Poly1305 ;
  - la clé est enveloppée par le Keystore Android, inutilisable téléphone verrouillé.
- **Apprentissage** : rien n'est appris dans un champ mot de passe, en navigation privée, dans une application incognito, téléphone verrouillé, ni pour un texte qui ressemble à un secret (carte, IBAN, code, clé d'API).
- **Geste panique** : 3 s sur la touche menu. Il détruit la clé (crypto-shredding).
- **Export et import** : chiffrés par phrase de passe (Argon2id), vers un fichier que tu choisis.
- **Limites, honnêtement** :
  - la dictée vocale passe par le service de reconnaissance du téléphone, qui peut être en ligne (Keyra prévient avant) ;
  - la clé de données reste en mémoire tant que le téléphone est déverrouillé ;
  - l'effacement de la mémoire Java est fait au mieux.

Détails :
- modèle de menace : `docs/threat-model.md` ;
- auto-audit OWASP MASVS : `docs/masvs-audit.md` ;
- décisions : `docs/adr/` ;
- signaler une faille : `SECURITY.md`.

Le dictionnaire français vient de [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0), et les noms français des emoji des [annotations Unicode CLDR](https://github.com/unicode-org/cldr-json).

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
- le manifeste : permissions et composants en liste blanche ;
- un build release reproductible (deux builds comparés octet par octet) ;
- CodeQL et Semgrep, plus un fuzzing nocturne et OpenSSF Scorecard.

Publication signée (APK v2/v3, Sigstore, SBOM) : `docs/release.md`. Contribuer : `CONTRIBUTING.md`.
