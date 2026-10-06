# Rapport de la phase 6 — Agrément et fonctions innovantes restantes

> 2026-10-06. Émulateur Pixel 9a.

## 1. Ce qui a été fait

| Élément | Fichier(s) | ADR |
|---------|-----------|-----|
| Vibration au choix (aucune, du téléphone, personnalisée avec intensité), son des touches, bulle d'aperçu | `keyboard/KeyFeedback`, `KeyboardView` | 0026 |
| Rangée de chiffres optionnelle | `KeyboardLayouts.build` | 0026 |
| Dispositions JSON AZERTY, BÉPO, QWERTY, QWERTZ, Dvorak ; parseur strict fuzzé | `keyboard/LayoutParser`, `assets/layouts` | 0027 |
| Tableau de transparence : preuves réseau D9 ; mots, paires, presse-papiers, extraits, suggestions masquées et incognito, avec recherche et suppression ; export, import et effacement total confirmés | `TransparencyActivity` | — |
| Extraits de texte chiffrés et expansion depuis le bandeau | `engine/Snippets`, `InputLogic.expandSnippet` | 0029 |
| Coffre biométrique des extraits protégés (activité transparente) | `security/SnippetVault`, `UnlockActivity` | 0029 |
| Export et import chiffrés : Argon2id et XChaCha20-Poly1305 en Rust | `keyra-core::backup`, `storage/Backup`, `LearningGate.restore` | 0028 |
| Presse-papiers : épingler, supprimer | déjà livré en phase 3 | — |

## 2. Vérifications

**Tests automatiques**
- **Rust** : 3 nouveaux tests pour `backup`, plus la cible de fuzzing `backup_header` ajoutée à la CI nocturne.
- **JVM** : 46 tests. Nouveaux :
  - `LayoutParserTest` (schéma, fichiers livrés, absence de chevauchement, 30 000 mutations) ;
  - `BackupFormatTest` (30 000 mutations) ;
  - test d'expansion des extraits ;
  - règle d'architecture `Backup.write` réservée à `LearningGate`.
- **Instrumentés** : 40 tests, dont l'export Argon2id réel à travers le JNI (148 ms sur l'émulateur).
- **Lint strict** et **garde-fou des journaux** : verts. La règle des Toast accepte désormais les ressources `R.string`, qui sont constantes.

**Essais réels sur l'émulateur**
- Tableau de transparence : « Keyra ne demande pas la permission INTERNET », et « Octets envoyés : 0 » selon `TrafficStats`.
- Extrait créé dans le tableau (« adr » → « 12 rue des Lilas »), puis taper « adr » dans un champ :
  - le bandeau affiche « ✎ 12 rue des Lilas » ;
  - le toucher remplace le raccourci ;
  - le clavier était en BÉPO avec la rangée de chiffres.

## 3. Limites
- **Extraits protégés** : le chemin biométrique n'a pas été essayé de bout en bout (il faut un code de verrouillage sur l'émulateur), ni sur GrapheneOS.
- **Modèle de toucher** : non conservé. Le tableau l'indique.
- **Vibration personnalisée et son** : pas d'essai physique. La vibration est sans effet sur l'émulateur.
