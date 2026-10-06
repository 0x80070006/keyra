# ADR-0024 — Coffre : XChaCha20-Poly1305 en Rust, clé de données hors de la JVM

- **Statut** : accepté (phase 3). Précise l'ADR-0005 (format) et l'ADR-0006 (enveloppe).

## Décision
- **Algorithme** : XChaCha20-Poly1305 (crate `chacha20poly1305` 0.11 de RustCrypto, sans la fonction `getrandom`).
  - Nonce de 192 bits tiré par `SecureRandom` côté Kotlin : aucun risque de collision, même avec des milliards d'enregistrements.
  - Temps constant en logiciel, sans dépendre des instructions AES du processeur.
- **Clé de données** :
  - désenveloppée par la clé maître du Keystore (AES-256-GCM, `KeyManager`), puis confiée **une fois** au cœur Rust (`vaultUnlock`) ; la copie Kotlin est aussitôt effacée ;
  - elle vit ensuite uniquement dans un `Zeroizing<[u8; 32]>` derrière un `Mutex` global ;
  - `vaultLock` (écran éteint, destruction du service, geste panique) l'efface.
- **Format** :
  - enregistrement = `nonce ‖ texte chiffré ‖ étiquette` ;
  - trame de journal = `longueur u32 LE ‖ enregistrement`.
  - Le contexte authentifié vaut `keyra/v1/<magasin>/<fichier>` : un fichier ne peut pas être substitué à un autre.
  - Une fin tronquée est ignorée ; une trame altérée arrête la lecture, puis un instantané propre est réécrit.
- **Bornes** : 1 Mio par enregistrement, 16 Mio par fichier lu.
- **Magasins** : `words`, `personal`, `emoji`, `clipboard`, `incognito`, `password_apps` (`EncryptedKv`).

## Dépendances ajoutées (exécution, dans `libkeyra_jni.so`)
`chacha20poly1305`, et ses dépendances RustCrypto `aead`, `chacha20`, `poly1305`, `cipher`, `universal-hash`, `crypto-common`, `inout`, `hybrid-array`, `typenum`, `cpufeatures`, `subtle`, `zeroize`. Toutes sous licence MIT ou Apache 2.0, vérifiées par `cargo-deny` et listées dans la SBOM. RustCrypto `chacha20poly1305` a été audité par NCC Group en 2020.

## Écarté
- **AES-GCM via `javax.crypto` avec la clé en `ByteArray`** : Conscrypt copie la clé dans des objets impossibles à effacer.
- **SQLCipher** : voir l'ADR-0005.

## Considérations de sécurité
- **Coffre verrouillé** : lecture vide, écritures refusées. Rien n'est mis en attente (l'apprentissage est de toute façon coupé quand l'appareil est verrouillé).
- **Risque résiduel** :
  - les données déchiffrées gardées en cache dans la JVM (tables `EncryptedKv`) restent en mémoire jusqu'à l'extinction de l'écran, qui vide les caches ;
  - la taille des fichiers révèle à peu près le volume de données.
