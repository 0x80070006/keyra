# Auto-audit OWASP MASVS v2, profil MAS-L2

> Keyra 12.0, 2026-10-06. Pour chaque exigence : statut, preuve dans le dépôt (code, test ou CI), ou justification.
>
> **Légende** : ✅ satisfait · ⚠️ satisfait avec une limite documentée · — sans objet (justifié).
>
> Ce document est une auto-évaluation, **pas un audit indépendant**. Il ne remplace pas un test d'intrusion.

## MASVS-STORAGE

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **STORAGE-1** : stocker les données sensibles de façon sûre | ✅ | Tout ce que Keyra conserve est dans `EncryptedKv` : XChaCha20-Poly1305, clé de données enveloppée par une clé AES-256 du Keystore (StrongBox si disponible, `setUnlockedDeviceRequired`). Instantané atomique et journal authentifié. ADR-0005, ADR-0006, ADR-0024. Tests : `VaultTest` (instrumenté), fuzzing `vault_journal`. Migration et effacement des données en clair de la 11.0 : `Migration11to12`. |
| **STORAGE-2** : empêcher les fuites de données sensibles | ✅ | Pas de sauvegarde (`allowBackup=false`, règles d'extraction vides). Aucun texte tapé dans les journaux, traces, Toast ou exceptions : tâche `logGuard` en CI, R8 retire `android.util.Log` en release. `FLAG_SECURE` sur les réglages, le tableau de transparence, l'invite biométrique et **la fenêtre du clavier** quand elle montre le presse-papiers ou sert un champ privé (corrigé pendant cet audit). Copies sensibles jamais écrites (`ClipboardHistory`, `SecretDetector`). |

## MASVS-CRYPTO

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **CRYPTO-1** : cryptographie forte et à jour | ✅ | Primitives de RustCrypto, jamais réimplémentées : XChaCha20-Poly1305 et Argon2id (64 Mio, 3 itérations). AES-256-GCM du Keystore. Nonces de 192 bits tirés par `SecureRandom`. Aucun algorithme obsolète (pas de MD5, de SHA-1, d'ECB ni de clé codée en dur). ADR-0024, ADR-0028. |
| **CRYPTO-2** : gestion des clés conforme aux bonnes pratiques | ⚠️ | Clé maître non exportable dans le Keystore, inutilisable appareil verrouillé. Clé de données en mémoire Rust (`Zeroizing`), effacée à l'extinction de l'écran. Crypto-shredding par le geste panique. Clé des extraits protégés liée à une authentification forte de moins de 30 s. **Limite** : la clé de données reste en mémoire tant que l'appareil est déverrouillé (D11, documenté). |

## MASVS-AUTH

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **AUTH-1** : authentification et autorisation sûres avec un serveur distant | — | Aucun compte, aucun serveur. |
| **AUTH-2** : authentification locale sûre selon la plateforme | ✅ | Extraits protégés : `BiometricPrompt` (`BIOMETRIC_STRONG` ou code de l'appareil), et clé Keystore à `setUserAuthenticationRequired`. Le contrôle est **cryptographique** : sans authentification, le déchiffrement échoue, quoi que fasse le code. ADR-0029. |
| **AUTH-3** : authentification supplémentaire pour les opérations sensibles | ✅ | Effacement total et export : confirmation explicite. Export : phrase de passe d'au moins 10 caractères, saisie deux fois. Extraits protégés : authentification à chaque usage, au-delà de 30 s. |

## MASVS-NETWORK

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **NETWORK-1** : trafic réseau sécurisé | — | Pas de permission `INTERNET` : Android refuse toute socket. Vérifié en CI (`tools/check_manifest.py`) et affiché dans le tableau de transparence (`PackageManager`, `TrafficStats`). |
| **NETWORK-2** : épinglage de certificats | — | Aucune connexion. |

## MASVS-PLATFORM

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **PLATFORM-1** : mécanismes d'IPC utilisés de façon sûre | ✅ | Seuls composants exportés : `MainActivity` (lanceur), le service de saisie (protégé par `BIND_INPUT_METHOD`, donc seul le système peut s'y lier) et `ProfileInstallReceiver` (protégé par `DUMP`, accessible seulement au shell et au système). Les composants sont en liste blanche en CI. Les résultats d'activités (`PendingInput`) sont liés au paquet et au champ d'origine, valables 60 s. Le récepteur d'écran est non exporté (`RECEIVER_NOT_EXPORTED`). |
| **PLATFORM-2** : WebViews sûres | — | Aucune WebView. |
| **PLATFORM-3** : interface utilisateur sûre | ✅ | `FLAG_SECURE` (voir STORAGE-2). En champ sensible : pas de surbrillance, de bulle, de son ni de zones adaptatives. TalkBack annonce « point » dans un champ mot de passe. Pavé PIN mélangé en option. Les extraits protégés ne montrent que leur raccourci. |

## MASVS-CODE

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **CODE-1** : plateforme à jour | ✅ | `targetSdk` et `compileSdk` 37, `minSdk` 29. |
| **CODE-2** : mécanisme de mise à jour | ⚠️ | Publications GitHub signées (APK v2 et v3, Sigstore) et F-Droid. Pas de mise à jour forcée : il n'y a ni réseau ni serveur. ADR-0031. |
| **CODE-3** : composants sans vulnérabilités connues | ✅ | Dépendances minimales (bibliothèque Kotlin, profileinstaller, crates RustCrypto). `cargo-deny` (avis RustSec, licences, sources), `verification-metadata.xml` (SHA-256), Dependabot, SBOM CycloneDX à chaque build. |
| **CODE-4** : valider et assainir toute entrée non fiable | ✅ | Le JNI vérifie la longueur de chaque tableau. Parseurs bornés et fuzzés : journal du coffre, dictionnaire, en-tête d'export (cargo-fuzz), dispositions et contenu d'export (mutations JVM). Les réponses `InputConnection` sont bornées à 1 000 caractères. Le cœur Rust est en `forbid(unsafe)`, avec `catch_unwind` aux frontières JNI. |

## MASVS-RESILIENCE

Le profil MAS-L2 n'exige pas MAS-R. Keyra est libre et doit pouvoir être vérifié et reconstruit : obscurcir le code irait contre ce but.

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **RESILIENCE-1** : intégrité de la plateforme | — | Pas de détection de root : GrapheneOS et les utilisateurs avancés sont la cible. Les données restent protégées par le Keystore. |
| **RESILIENCE-2** : mécanismes anti-falsification | ⚠️ | Signature APK v2 et v3, build reproductible vérifié en CI, Sigstore. Pas de vérification de signature à l'exécution. |
| **RESILIENCE-3** : mécanismes anti-analyse statique | — | Code source public. R8 réduit le code, sans but de protection. |
| **RESILIENCE-4** : mécanismes anti-analyse dynamique | ⚠️ | Release non débogable et non profileable par le shell (vérifié en CI). Pas d'anti-débogage. |

## MASVS-PRIVACY

| Exigence | Statut | Preuve ou justification |
|----------|--------|-------------------------|
| **PRIVACY-1** : minimiser l'accès aux données sensibles | ✅ | Deux permissions *normales* seulement (`VIBRATE`, `USE_BIOMETRIC`). Pas de `QUERY_ALL_PACKAGES`. Presse-papiers lu seulement clavier affiché. Rien appris en champ privé, en incognito ou appareil verrouillé, ni pour un secret (`LearningGate`, vérifié par `ArchitectureTest`). Pas de modèle de toucher conservé. |
| **PRIVACY-2** : empêcher l'identification de l'utilisateur | ✅ | Pas d'identifiant, de télémétrie ni de compte. Aucune donnée ne sort du téléphone, sauf l'export, choisi par l'utilisateur et chiffré. |
| **PRIVACY-3** : transparence sur la collecte et l'usage | ✅ | Tableau de transparence avec preuves réseau système (D9). Page Confidentialité du clavier avec ses limites. Avertissement avant la première dictée (le service système peut être en ligne). |
| **PRIVACY-4** : contrôle de l'utilisateur sur ses données | ✅ | Lister, rechercher, modifier et supprimer chaque donnée. Exporter et importer. Geste panique et « Tout effacer ». Applications incognito réglables. |

## Corrigé pendant l'audit
- La fenêtre du clavier n'était pas en `FLAG_SECURE` : le presse-papiers et les champs privés pouvaient apparaître dans une capture ou un enregistrement d'écran. Corrigé par `MintKeyboard.updateSecureWindow`.

## Reste à faire hors du dépôt
- **Essais sur un Pixel 9a physique sous GrapheneOS** : StrongBox, invite biométrique depuis l'IME, vibration personnalisée.
- **Protection de la branche `main`** et revue obligatoire sur GitHub (voir `CONTRIBUTING.md`). C'est un réglage du dépôt, à activer par son propriétaire.
- **Audit indépendant.**
