# ADR-0021 — Kotlin et Rust, reliés par un pont JNI minimal (remplace l'ADR-0001)

- **Statut** : accepté (phase 1, décision de l'utilisateur : « je veux du Kotlin et du Rust »)
- **Remplace** : ADR-0001

## Décision
- **Kotlin** reste responsable de tout ce qui touche à Android : service IME, vues, `InputConnection`, Android Keystore, réglages.
- **Rust** porte le **cœur pur**, en deux crates dans `rust/` :
  - `keyra-core` : fonctions déterministes, sans entrée-sortie, avec `#![forbid(unsafe_code)]` ;
  - `keyra-jni` : le **seul** code `unsafe`. Il fait le lien avec la JVM, et chaque bloc porte un commentaire `// SAFETY:`.
- Ce qui passe en Rust, par phase :
  - phase 1 : détecteur de secrets ;
  - phase 3 : format de stockage chiffré et AEAD, la clé de données vivant en mémoire Rust effaçable (`zeroize`) ;
  - phase 5 : dictionnaire binaire et moteur de prédiction ;
  - phase 6 : parseur de dispositions, et Argon2id pour l'export, ce qui remplace Bouncy Castle (ADR-0020).

## Pourquoi un JNI écrit à la main plutôt qu'UniFFI
UniFFI génère des liaisons Kotlin qui reposent sur **JNA** :
- c'est une dépendance d'exécution, avec sa propre bibliothèque native `libjnidispatch.so` ;
- les appels sont résolus par réflexion ;
- il faut des règles R8 dédiées.

Cela contredit l'objectif « zéro dépendance d'exécution » (ADR-0013 à 0016). Notre surface est petite : un `byte[]` en entrée, un entier en sortie. Un pont JNI d'une centaine de lignes, sans dépendance hormis `jni-sys` (types FFI seulement), reste lisible en une seule revue. **On réexaminera UniFFI** si la surface devient riche (structures, rappels), par exemple pour le moteur de prédiction.

## Règles du pont
- Le texte passe en `byte[]` UTF-8, jamais en `String` Java. On évite ainsi l'UTF-8 modifié, et le tampon est effacé des deux côtés après l'appel.
- Taille bornée (`MAX_INPUT_BYTES`, 16 Kio).
- Toute erreur renvoie `-1`, et Kotlin **échoue fermé** : le texte est alors traité comme secret.
- Aucune panique ne traverse la frontière (`catch_unwind`).
- `abiVersion()` vérifie que la bibliothèque native correspond au code Kotlin.
- Tests : unitaires et par propriétés en Rust (`cargo test`), fuzzing (`cargo fuzz`, CI nocturne), et tests instrumentés Android du pont réel (`KeyraCoreTest`).

## Conséquences
- Le build demande Rust 1.99.0 (épinglé dans `rust/rust-toolchain.toml`) et le NDK 30.0.16248370 (épinglé dans `app/build.gradle.kts`).
- ABI livrées : `arm64-v8a` (Pixel 9a) et `x86_64` (émulateur). Pas de 32 bits.
- Chaque bibliothèque `.so` ajoute environ 290 Ko à l'APK.

## Considérations de sécurité
- Le risque mémoire se concentre dans `keyra-jni` : 3 blocs `unsafe`, tous commentés et couverts par `KeyraCoreTest`.
- Une `.so` compromise au build serait du code natif dans le processus clavier. On s'en protège par `Cargo.lock` utilisé en `--locked`, `cargo-deny` et la comparaison de deux builds reproductibles (ADR-0023).
