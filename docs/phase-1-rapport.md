# Rapport de la phase 1 — Durcissement, cœur Rust, CI

> 2026-10-06. Décisions de l'utilisateur prises en compte :
> - Kotlin **et** Rust (ADR-0021, qui remplace l'ADR-0001) ;
> - `minSdk 29` (ADR-0004 accepté) ;
> - dépôt GitHub **public** `0x80070006/keyra`, auteur des commits en adresse noreply.
>
> Mesures : `PERF.md`. Les mesures sur le Pixel 9a restent à faire, à la demande de l'utilisateur.

## 1. Ce qui a été fait

| Commit | Contenu | Constats corrigés |
|--------|---------|-------------------|
| Cœur Rust | `rust/keyra-core` (pur, `forbid(unsafe_code)`) : détecteur de secrets. `rust/keyra-jni` : pont JNI minimal. Tâche Gradle `cargoBuild` sans plugin, NDK 30 et Rust 1.99.0 épinglés. | prépare S2 et S3 |
| `SecurityPolicy` | Politique unique par champ. Navigation privée, application incognito et appareil verrouillé : rien n'est appris ni conservé. `SecretDetector` (Rust) filtre l'apprentissage et l'historique du presse-papiers. | **S1**, **S10**, S2 et S3 en partie |
| Sauvegardes et vie privée | `dataExtractionRules` et `fullBackupContent` (exclusion totale, y compris le transfert entre appareils), `FLAG_SECURE` sur les réglages, avertissement de dictée, `PendingInput` lié au champ et limité à 60 s | **S5**, **S6**, **S7**, **S9** |
| Zéro dépendance | Retrait de Compose, core-ktx et lifecycle, R8 activé, journaux supprimés en release, `minSdk 29`, ABI 64 bits, plus de JDK téléchargé automatiquement | **E-1**, **E-3**, T-2 |
| Garde-fous | `logGuard` (aucun texte variable dans les journaux, les traces, les Toast ou les exceptions) ; clippy interdit `println!` et `dbg!` ; Lint strict avec liste de référence | I-8 |
| Chaîne d'approvisionnement | `verification-metadata.xml` (369 composants, SHA-256), `cargo-deny`, `Cargo.lock --locked` | T-1 |
| CI | GitHub Actions : Rust, Android, tests instrumentés, build reproductible comparé octet par octet, contrôle du manifeste, SBOM, CodeQL, Semgrep, fuzzing nocturne, Scorecard, Dependabot. Actions épinglées par empreinte de commit. | S8 en partie, T-1, T-4 |
| Dépôt GitHub | Public. Signalement privé des vulnérabilités, alertes Dependabot, détection de secrets avec blocage à l'envoi, jeton CI en lecture seule par défaut, wiki désactivé. | — |

## 2. Résultats mesurés

- **APK de release : 8,6 Mo → 1,17 Mo.** Code compilé (dex) : 21,8 Mo → 162 Ko.
- **Dépendances d'exécution** : une seule côté JVM (`kotlin-stdlib`), deux côté Rust (`jni-sys`, `zeroize`), sans dépendance transitive.
- **Manifeste de release** : aucune permission ; 4 composants, tous de Keyra.
- **Tests** :
  - 16 tests JVM ;
  - 12 tests Rust, dont 3 par propriétés ;
  - 19 tests instrumentés sur l'émulateur, dont 5 du pont JNI réel.
- **Analyse statique** : clippy en mode pedantic sans avertissement ; Semgrep sans résultat (4 usages `unsafe` audités et annotés) ; garde-fou des journaux vérifié sur une sonde de fuite (5 fautes détectées sur 5).
- Les références de la phase 0 sont inchangées (zones mortes 26,7 %, 4 reconstructions, correcteur 79,5 %) : la phase 1 ne touche pas à la saisie.

## 3. Considérations de sécurité, fichier par fichier

**`rust/keyra-core/src/secret.rs`**
- Heuristiques volontairement simples. Faux négatifs connus : une phrase de passe faite de mots du dictionnaire (« Cheval-Pile_Agrafe ») ; un mot de passe court (moins de 12 caractères) sans chiffre.
- Faux positifs acceptés : un code à 4 chiffres comme « 2026 » copié seul, une empreinte hexadécimale (commit git). Ils ne sont simplement pas conservés dans l'historique.
- Le texte analysé est borné à 16 Kio.

**`rust/keyra-jni/src/lib.rs`**
- Seul code `unsafe` : 3 blocs. Ce qui pourrait mal tourner :
  - un appel JNI avec un `env` invalide, impossible si l'appel vient de la JVM ;
  - une exception Java en attente : détectée par `ExceptionCheck`, avec échec fermé.
- Une panique Rust est rattrapée et renvoie `-1`. La copie du texte est effacée par `zeroize`, mais la `String` Kotlin d'origine reste soumise au ramasse-miettes (ADR-0011).

**`core/KeyraCore.kt`**
- Échec fermé si la `.so` est absente ou si `abiVersion()` ne correspond pas : plus rien n'est appris ni gardé dans l'historique.
- Risque : une `.so` d'une autre version chargée par erreur. C'est couvert par `abiVersion`.

**`security/SecretDetector.kt`**
- On n'apprend jamais un mot contenant un chiffre.
- Les e-mails et numéros de téléphone ne sont pas appris, mais restent possibles dans l'historique du presse-papiers. C'est un choix d'usage, documenté.

**`security/SecurityPolicy.kt`**
- Point unique de décision.
- Ce qui pourrait mal tourner : un futur code qui teste `inputType` lui-même au lieu de passer par la politique. À surveiller en revue ; `SecurityPolicyTest` fixe le comportement attendu.

**`MintInputService.kt`**
- La politique est recalculée à chaque `onStartInput` et `onStartInputView`. L'état « verrouillé » est lu au début du champ : un verrouillage *pendant* la saisie n'est pris en compte qu'au champ suivant (risque résiduel faible).
- La lecture de la liste incognito par application viendra en phase 3 : aujourd'hui l'ensemble est vide.

**`ClipboardHistory.kt`**
- Une copie classée secrète, ou non analysable, n'est jamais écrite.
- L'historique existant reste en clair dans les SharedPreferences jusqu'à la phase 3 (chiffrement).

**`MediaInputActivity.kt`**
- L'avertissement nomme le service de reconnaissance et réapparaît s'il change.
- `<queries>` est limité à l'action `RECOGNIZE_SPEECH` : pas de visibilité générale sur les paquets installés.

**`res/xml/data_extraction_rules.xml` et `backup_rules.xml`**
- Exclusion totale. À vérifier sur l'appareil avec `adb shell bmgr` (sauvegarde) et lors d'un vrai transfert de téléphone.

**`app/build.gradle.kts` (`cargoBuild`, `logGuard`, `sbom`)**
- `cargoBuild` exécute le `cargo` du poste ou de la CI (ADR-0023).
- `logGuard` repose sur des expressions régulières : un contournement délibéré reste possible, par exemple via une variable intermédiaire qui contient un `Log`. Il protège des erreurs, pas d'un contributeur malveillant ; la revue reste nécessaire.

**`rules.keep`**
- Les appels à `android.util.Log` sont supprimés en release, mais les arguments sont parfois encore évalués. Sans conséquence ici : `logGuard` interdit `Log` dans tout le code.

**`tools/check_manifest.py` et `docs/allowed-*.txt`**
- Toute nouvelle permission ou tout nouveau composant fait échouer la CI. On ne peut l'ajouter qu'avec un ADR.

**`.github/workflows/*`**
- Actions épinglées par empreinte, jeton en lecture seule, `persist-credentials: false`.
- `android-emulator-runner` est une action tierce. Elle ne reçoit aucun secret et ne s'exécute que dans le job de tests instrumentés.
- La SBOM et l'APK publiés comme artefacts ne sont **pas signés** : la signature arrive en phase 7.

**`gradle/verification-metadata.xml`**
- Les sommes d'aapt2 pour Linux et macOS ont été ajoutées à la main, après recoupement du SHA-1 avec celui publié par Google.
- Une mise à jour de dépendance exige de régénérer ce fichier (`--write-verification-metadata sha256`) et de relire la différence.

## 4. Écarts par rapport au prompt

1. **Pont JNI écrit à la main, sans UniFFI** : UniFFI imposerait JNA comme dépendance d'exécution (ADR-0021). Réexamen prévu en phase 5.
2. **detekt** n'est pas ajouté. Ses règles de style recouvrent clippy et Lint, et la règle « journaux » est couverte par `logGuard`, plus précis pour ce besoin. À reconsidérer si la base Kotlin grossit.
3. **SBOM générée par une tâche maison**, plutôt que par le plugin CycloneDX : une dépendance de build en moins, et une sortie déterministe.
4. **Branche protégée et commits signés** : prévus en phase 7. Les activer maintenant bloquerait l'envoi direct sur `main` pendant le chantier.
5. **Mesures sur le Pixel 9a** : reportées à la demande de l'utilisateur.

## 5. Avant la phase 2

- Résultat de la première CI sur GitHub : voir l'onglet Actions. Les jobs « tests instrumentés » et « build reproductible » sont les plus susceptibles de demander un ajustement à leur premier passage.
- Score OpenSSF Scorecard : publié après le premier passage du workflow.
