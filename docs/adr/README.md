# Architecture Decision Records — Keyra

Format : contexte, décision, conséquences, considérations de sécurité. Un ADR accepté ne se modifie pas : on le remplace par un nouvel ADR qui le cite.

| ADR | Sujet | Statut |
|-----|-------|--------|
| [0001](0001-coeur-kotlin-sans-rust.md) | Cœur en Kotlin, sans Rust ni JNI | remplacé par 0021 |
| [0002](0002-pas-de-compose.md) | Pas de Jetpack Compose | accepté |
| [0003](0003-licence-mit.md) | Licence MIT et réutilisation de code | accepté |
| [0004](0004-android-minsdk-29.md) | Android seulement, minSdk 29 | accepté |
| [0005](0005-stockage-chiffre-sans-sqlcipher.md) | Stockage chiffré par fichiers, sans SQLCipher | accepté |
| [0006](0006-chiffrement-par-enveloppe.md) | Chiffrement par enveloppe, rien du Keystore pendant la frappe | accepté |
| [0007](0007-retours-sensoriels-selon-securitypolicy.md) | Retours visuels et sonores selon `SecurityPolicy` | accepté |
| [0008](0008-learninggate.md) | `LearningGate`, seul accès à l'apprentissage | accepté |
| [0009](0009-preuves-reseau-verifiables.md) | Preuves réseau vérifiables | accepté |
| [0010](0010-dictee-honnete.md) | Dictée : avertissement, Whisper reporté | accepté |
| [0011](0011-effacement-memoire-au-mieux.md) | Effacement mémoire au mieux | accepté |
| [0012](0012-validation-au-relachement.md) | Validation au relâchement | accepté, à mesurer en phase 2 |
| [0013](0013-dependance-kotlin-stdlib.md) | Dépendance conservée : Kotlin stdlib | accepté |
| [0014](0014-dependance-androidx-core-ktx.md) | Retrait de core-ktx | accepté |
| [0015](0015-dependance-androidx-lifecycle.md) | Retrait de lifecycle-runtime-ktx | accepté |
| [0016](0016-dependances-compose.md) | Retrait de Compose et activity-compose | accepté |
| [0017](0017-outils-de-test.md) | Outils de test | accepté |
| [0018](0018-macrobenchmark-uiautomator.md) | Macrobenchmark et UiAutomator | accepté |
| [0019](0019-plugin-foojay-resolver.md) | Retrait du plugin foojay-resolver | accepté |
| [0020](0020-dependances-prevues.md) | Dépendances prévues | proposé |
| [0021](0021-kotlin-et-rust-jni-minimal.md) | Kotlin et Rust, pont JNI minimal | accepté |
| [0022](0022-dependances-rust.md) | Dépendances Rust | accepté |
| [0023](0023-chaine-de-build-rust.md) | Chaîne de build Rust | accepté |
| [0024](0024-coffre-xchacha20-en-rust.md) | Coffre XChaCha20-Poly1305 en Rust | accepté |
| [0025](0025-moteur-de-prediction-rust.md) | Moteur de prédiction en Rust | accepté |
| [0026](0026-retour-haptique-et-son.md) | Retour haptique et son, permission VIBRATE | accepté |
| [0027](0027-dispositions-json.md) | Dispositions JSON, parseur strict | accepté |
| [0028](0028-export-chiffre-argon2id.md) | Export chiffré Argon2id | accepté |
| [0029](0029-extraits-et-coffre-biometrique.md) | Extraits et coffre biométrique | accepté |
| [0030](0030-baseline-profile.md) | Baseline Profile et profileinstaller | accepté |
| [0031](0031-signature-et-publication.md) | Signature v3.1, Sigstore, SBOM | accepté |
