# ADR-0023 — Chaîne de build Rust : épinglage, cargo-deny, pas de plugin Gradle tiers

- **Statut** : accepté (phase 1)

## Décision
- **Rust épinglé** à `1.99.0` (`rust/rust-toolchain.toml`) : la même version sur le poste de dev, en CI et pour F-Droid.
- **NDK épinglé** à `30.0.16248370` (`ndkVersion`), installé par `sdkmanager` depuis le dépôt officiel de Google.
- **Cargo** :
  - `Cargo.lock` est versionné, et le build passe toujours `--locked` ;
  - `cargo-deny` vérifie licences, avis de sécurité (RustSec), versions retirées et sources autorisées (`rust/deny.toml`).
- **Pas de plugin Gradle tiers pour Rust** (`rust-android-gradle` ou équivalent). La tâche `cargoBuild` de `app/build.gradle.kts` (environ 50 lignes) appelle `cargo` avec l'éditeur de liens `clang` du NDK, puis copie les `.so` dans les `jniLibs` générés.
- **Reproductibilité** :
  - `--remap-path-prefix` efface les chemins de la machine ;
  - profil release avec `codegen-units = 1`, `lto = true` et `strip = true` ;
  - alignement des pages à 16 Ko (`-z max-page-size=16384`), exigé à partir d'Android 15.
- **Poste Windows** : la chaîne hôte est `x86_64-pc-windows-msvc`, avec les Visual Studio Build Tools (charge de travail C++). La chaîne GNU a été écartée, car il lui manque l'assembleur requis par `dlltool` pour les tests sur la machine de dev.

## Considérations de sécurité
- La tâche `cargoBuild` exécute `cargo` trouvé par la propriété Gradle `keyra.cargo`, sinon la variable `CARGO`, sinon `~/.cargo/bin/cargo`. Un `cargo` piégé sur le poste de dev compromettrait le build ; c'est hors du modèle de menace (machine de confiance), et la CI part d'une image propre.
- Les crates sont téléchargées depuis crates.io, puis vérifiées par les sommes de `Cargo.lock`.
