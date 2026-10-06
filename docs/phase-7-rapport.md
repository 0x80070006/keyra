# Rapport de la phase 7 et rapport final — Keyra 12.0

> 2026-10-06. Fin du plan de `PROMPT_FUSIONNE.md` (phases 0 à 7).

## 1. Phase 7 : ce qui a été fait

| Élément | Fichier(s) | État |
|---------|-----------|------|
| Auto-audit OWASP MASVS v2, profil MAS-L2 | `docs/masvs-audit.md` | ✅ Une faille corrigée : la fenêtre du clavier passe en `FLAG_SECURE` quand elle montre le presse-papiers ou sert un champ privé |
| Baseline Profile et profileinstaller | `app/src/main/baseline-prof.txt`, `BaselineProfileGenerator`, ADR-0030 | ✅ 1 191 règles ; build de release toujours reproductible |
| Signature v2, v3 et v3.1, cosign, SBOM jointe | `.github/workflows/release.yml`, `docs/release.md`, ADR-0031 | ✅ Workflow prêt. ⚠️ Pas encore de publication réelle : la clé et les secrets sont à créer par le propriétaire |
| Métadonnées F-Droid | `fastlane/metadata/android/fr-FR/` | ✅ Titre, descriptions, journal des modifications 12, icône 512 px. ⚠️ `fdroid build` local non fait (il faut un hôte Linux avec fdroidserver) |
| `SECURITY.md`, `CONTRIBUTING.md` | racine | ✅ Signalement privé GitHub et délais. ⚠️ Pas encore de clé PGP : elle sera publiée sur demande |
| Documentation utilisateur | `README.md`, page Confidentialité et notes de version du clavier, `docs/release-notes.md` | ✅ Avec les limites (dictée système, effacement au mieux, clé en mémoire appareil déverrouillé) |
| Version | `versionCode 12`, `versionName "12.0"` | ✅ |
| SBOM | tâche `:app:sbom` | ✅ Corrigée : les bibliothèques AAR sont maintenant reconnues par leur identifiant de composant |

## 2. Bilan de Keyra 12.0 (phases 0 à 7)

| Domaine | 11.0 (phase 0) | 12.0 |
|---------|----------------|------|
| Frappe | une vue par touche, zones mortes, reconstruction à chaque Maj | une vue Canvas, touche la plus proche, phantom up, aucune reconstruction en tapant |
| Correction juste / fausse | 79,5 % / 4,0 % | **83,5 % / 1,5 %** |
| Coût par frappe, p95 (émulateur) | 32,9 ms | **1,53 ms** |
| Chargement du dictionnaire | ≈ 10 s | **117 ms** |
| Stockage | en clair (SharedPreferences) | chiffré (XChaCha20-Poly1305, Keystore), crypto-shredding |
| Permissions | aucune | `VIBRATE`, `USE_BIOMETRIC` (normales, ADR) ; toujours **pas d'`INTERNET`** |
| Tests | quelques tests | 46 tests JVM, 40 instrumentés, 28 tests Rust et 4 cibles de fuzzing ; CI avec CodeQL, Semgrep, build reproductible et Scorecard |

## 3. Ce qui reste hors du dépôt (à faire par le propriétaire)
1. **Clé de release et secrets** de l'environnement GitHub `release` (`docs/release.md` §1), puis étiquette `v12.0` pour la première publication signée.
2. **Protection de la branche `main`** (`CONTRIBUTING.md`) : je ne l'ai pas activée, car c'est un réglage du dépôt. Une revue obligatoire empêcherait aussi les envois directs. Commande possible :

   ```bash
   gh api -X PUT repos/0x80070006/keyra/branches/main/protection --input protection.json
   ```

   avec `required_status_checks` (CI), `required_pull_request_reviews`, `allow_force_pushes=false` et `required_signatures` (point d'accès séparé).

   Ce réglage, la revue de code et les publications signées font partie de ce que mesure Scorecard (5,4 aujourd'hui, objectif ≥ 8).
3. **Essais sur un Pixel 9a physique sous GrapheneOS** : StrongBox, invite biométrique des extraits protégés lancée depuis le clavier, vibration personnalisée, mesures de latence de référence (l'utilisateur les a reportées).
4. **`fdroid build` local** et demande d'inclusion à F-Droid.

## 4. Reports justifiés (section 8 du prompt), confirmés
Frappe gestuelle, petit modèle de langage local, dictée Whisper hors ligne, synchronisation via une application compagnon, multilingue automatique, clavier flottant. Aucune n'a commencé ; les conditions de reprise sont inchangées. Ajout : le **modèle de toucher personnel** (décalage moyen par touche) est lui aussi reporté (phase 5, rapport §5).
