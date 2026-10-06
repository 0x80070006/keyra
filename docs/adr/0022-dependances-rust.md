# ADR-0022 — Dépendances Rust

- **Statut** : accepté (phase 1)

| Crate | Version | Portée | Raison | Dépendances transitives |
|-------|---------|--------|--------|-------------------------|
| `jni-sys` | `=0.3.0` (épinglée) | exécution, `keyra-jni` | Types et table de fonctions JNI. On ne veut que les déclarations FFI, pas d'abstraction. | **aucune** |
| `zeroize` | 1.9 | exécution, `keyra-jni` | Effacer les tampons de texte, sans que le compilateur supprime l'effacement. Projet RustCrypto. | aucune avec les fonctions utilisées |
| `proptest` | 1.11 | tests seulement | Tests par propriétés (absence de panique, cartes valides détectées, mots ordinaires non signalés) | une trentaine, jamais dans l'APK |
| `libfuzzer-sys` | 0.4 | fuzzing seulement (`rust/fuzz`, hors workspace) | Lier libFuzzer pour `cargo fuzz` | jamais dans l'APK |

## Écartés
- **`jni` (crate de haut niveau)** : il tire 6 à 10 crates (`combine`, `cesu8`, `thiserror`…) pour une API dont on n'utilise que trois fonctions.
- **`jni-sys` 0.4** : il ajoute un crate de macros procédurales (`jni-sys-macros`, donc `syn` et `quote` au build) sans bénéfice pour nous.
- **UniFFI** : voir ADR-0021.

## Considérations de sécurité
Les dépendances d'exécution se réduisent à deux crates sans dépendance transitive, auditables en entier. `cargo-deny` refuse tout registre ou dépôt git inconnu, toute licence hors liste, et toute version retirée (« yanked ») ou faisant l'objet d'un avis de sécurité.
