# Rapport de la phase 3 — Stockage chiffré et défenses de vie privée

> 2026-10-06. Émulateur Pixel 9a (clé maître de niveau « logicielle » sur l'émulateur ; StrongBox attendu sur le Pixel 9a).

## 1. Ce qui a été fait

| Élément | Fichier(s) | Constat ou fonction |
|---------|-----------|---------------------|
| Coffre XChaCha20-Poly1305 en Rust, journal tolérant aux coupures | `rust/keyra-core/src/vault.rs`, `rust/keyra-jni` | S4, ADR-0024 |
| Clé maître Keystore (StrongBox, sinon TEE), inutilisable appareil verrouillé ; clé de données enveloppée | `storage/KeyManager.kt` | ADR-0006 |
| Magasin clé-valeur chiffré : instantané atomique et journal, compactage | `storage/EncryptedKv.kt` | ADR-0005 |
| Mots appris, mots personnels, emoji, presse-papiers chiffrés | `UserLexicon.kt`, `EmojiHistory.kt`, `ClipboardHistory.kt` | **S3, S4** |
| Migration de la 11.0 : import, puis suppression des fichiers en clair ; les copies secrètes ne sont pas reprises | `storage/Migration11to12.kt` | S3, S4 |
| Point d'entrée unique de l'apprentissage | `security/LearningGate.kt`, `ArchitectureTest` | **S2**, ADR-0008 |
| Presse-papiers éphémère : expiration réglable, épinglage, suppression ; copie sensible jamais écrite ; effacement optionnel du presse-papiers système après 30 s | `ClipboardHistory.kt`, `MintInputService.captureClip` | fonction n° 3 |
| Incognito par application : 30 paquets vérifiés sur Google Play ou F-Droid ; choix de l'utilisateur ; proposition des applications où un champ mot de passe a été vu ; indicateur dans la barre d'outils | `security/IncognitoApps.kt` | fonction n° 4 |
| Geste panique : 3 s sur la touche menu puis glisser, ou bouton dans les réglages ; crypto-shredding et arrêt du processus | `security/Panic.kt` | fonction n° 6 |
| Coffre fermé à l'extinction de l'écran, rouvert au déverrouillage | `MintInputService.vaultReceiver` | ADR-0006 |

## 2. Tests

- **Rust** : 18 tests, dont 4 par propriétés. Ils couvrent l'aller-retour, la mauvaise clé, le mauvais contexte, l'altération, le journal coupé et une lecture qui ne panique jamais. Une nouvelle cible de fuzzing, `vault_journal`, s'ajoute.
- **JVM** : 25 tests, dont `ArchitectureTest`, qui vérifie que seul `LearningGate` écrit dans les magasins.
- **Instrumentés** : 35 tests, dont `VaultTest`, sur le vrai Keystore et le vrai cœur Rust :
  - aller-retour à travers un verrouillage du coffre ;
  - **aucun texte en clair** dans tout le dossier de données de l'application ;
  - écriture interrompue ;
  - fichier altéré, ou échangé entre deux magasins ;
  - **geste panique : une copie restaurée des anciens fichiers reste illisible** ;
  - migration avec effacement des fichiers en clair, sans reprendre la carte bancaire ;
  - règles d'apprentissage : navigation privée, appareil verrouillé, application incognito, secret, chiffre ;
  - expiration et épinglage du presse-papiers ;
  - incognito.
- **Essai réel sur l'émulateur** : frappe sans plantage, coffre créé en accès privé.

## 3. Considérations de sécurité, fichier par fichier

**`vault.rs`**
- Nonce fourni par l'appelant. S'il était réutilisé avec la même clé, la confidentialité de ces deux enregistrements serait compromise. Il vient de `SecureRandom` (192 bits), sans compteur à gérer.

**`keyra-jni`**
- 6 nouveaux blocs `unsafe` (`NewByteArray`, `SetByteArrayRegion`), commentés et couverts par `VaultTest`.
- Le texte déchiffré est copié vers la JVM, puis la copie Rust est effacée.

**`KeyManager.kt`**
- Si la clé maître est perdue (réinstallation, panique), le coffre repart de zéro au lieu de planter : les données sont perdues, c'est volontaire.
- Un appareil verrouillé ne déclenche jamais cette remise à zéro.

**`EncryptedKv.kt`**
- Les tables déchiffrées restent en cache dans la JVM jusqu'à l'extinction de l'écran (ADR-0011).
- Le journal n'est pas synchronisé (`fsync`) à chaque écriture : une coupure de courant peut perdre la dernière écriture, mais jamais corrompre le reste.

**`ClipboardHistory.kt`**
- L'expiration n'est appliquée qu'à la lecture : une copie expirée reste chiffrée sur disque jusqu'à la prochaine ouverture du panneau ou capture.
- L'option d'effacement du presse-papiers système compare une empreinte SHA-256, sans garder le texte.

**`IncognitoApps.kt`**
- La liste des applications où un champ mot de passe a été vu révèle quelles applications l'utilisateur utilise. Elle est chiffrée et effacée par le geste panique.
- Sur appareil verrouillé, seule la liste embarquée s'applique.

**`Panic.kt`**
- `Process.killProcess` purge le tas ; le système relance le clavier.
- Les fichiers supprimés peuvent subsister physiquement sur la mémoire flash, mais ils sont illisibles sans la clé maître détruite (vérifié par test).

**`Migration11to12.kt`**
- `commit()` est volontaire pour que l'effacement atteigne le disque. Les anciennes données en clair peuvent subsister physiquement sur la mémoire flash : risque résiduel documenté.

**`SettingsActivity.kt`**
- Elle ouvre le coffre à l'ouverture (`FLAG_SECURE` déjà actif) et affiche les paquets incognito.

## 4. Limites et suites

- La **dictée** reste déléguée au service système (ADR-0010).
- Le **tableau de transparence** (consulter et modifier ce qui a été appris) arrive en phase 6.
- **Coffre de StrongBox** : à confirmer sur le Pixel 9a. La mesure n'a pas été faite, à la demande de l'utilisateur.
