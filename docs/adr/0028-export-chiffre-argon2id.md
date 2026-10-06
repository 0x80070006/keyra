# ADR-0028 — Export et import chiffrés : Argon2id et XChaCha20-Poly1305 en Rust

- **Statut** : accepté (phase 6).

## Décision
**Format**, entiers en petit-boutiste :

```
"KEYRAEXP" ‖ version ‖ sel (16) ‖ mémoire Kio ‖ itérations ‖ voies ‖ nonce (24) ‖ chiffré ‖ étiquette
```

**Cryptographie** (`keyra-core::backup`) :
- **Argon2id** du crate `argon2` 0.6 (RustCrypto, MIT ou Apache-2.0), jamais réimplémenté. Paramètres d'export : 64 Mio, 3 itérations, 1 voie. Mesure : 148 ms sur l'émulateur.
- **Chiffrement XChaCha20-Poly1305**, comme le coffre (ADR-0024). Tout l'en-tête est en données associées : baisser les paramètres d'un fichier le rend invalide.

**Bornes à l'import**, vérifiées *avant* tout calcul :
- mémoire entre 64 et 512 Mio, itérations entre 3 et 16, voies entre 1 et 4 ;
- 16 Mio au plus.

**Contenu** :
- exportés : mots, mots personnels, paires, suggestions masquées, emoji, choix incognito, extraits non protégés ;
- **jamais le presse-papiers**, éphémère par conception ;
- **pas les extraits protégés** : leur clé Keystore ne quitte pas le téléphone.

**Phrase de passe** :
- au moins 10 caractères, saisie deux fois ;
- gardée en `CharArray` puis en `ByteArray`, effacés après usage ;
- elle traverse un `Editable` du système, que l'on vide (risque résiduel D11).

**Import** :
- décodage strict et borné (`Backup.decode` : magasins connus, 100 000 entrées) ;
- écriture via `LearningGate.restore`, qui réapplique `SecretDetector` aux mots et aux paires et refuse les extraits marqués protégés ;
- fusion avec l'existant.

**Fichiers** : choisis par l'utilisateur (`ACTION_CREATE_DOCUMENT` et `ACTION_OPEN_DOCUMENT`) depuis le tableau de transparence, jamais depuis l'IME. Rien n'est envoyé.

## Écart par rapport au prompt
Le prompt demandait AES-256-GCM et Bouncy Castle. La révision D1 (Kotlin et Rust) place la cryptographie dans le cœur Rust, qui utilise déjà XChaCha20-Poly1305 :
- une seule primitive AEAD dans le projet ;
- aucune dépendance Java en plus.

## Tests
- **Rust** : aller-retour, mauvaise phrase, en-tête authentifié, paramètres trop faibles ou trop lourds.
- **Fuzzing** : cible `backup_header`.
- **JVM** : `BackupFormatTest`, avec 30 000 mutations.
- **Instrumenté** : Argon2id réel à travers le JNI.
