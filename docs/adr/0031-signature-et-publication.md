# ADR-0031 — Signature APK v2/v3/v3.1, Sigstore et SBOM à chaque publication

- **Statut** : accepté (phase 7).

## Décision
- **Clé de release hors du dépôt** : secrets d'un environnement GitHub protégé (`release`), décodés dans `$RUNNER_TEMP` avec `umask 077` puis supprimés. Le build Gradle reste non signé : c'est l'APK que F-Droid et les vérificateurs reproduisent.
- **Signature** : `apksigner` avec v1 désactivée (`minSdk` 29), v2 et v3 activées. Une **lignée de rotation** facultative active la v3.1 (`--rotation-min-sdk-version 33`). Procédure : `docs/release.md`.
- **Sigstore** : `cosign sign-blob` sans clé, avec l'identité OIDC du workflow. L'action `sigstore/cosign-installer` est épinglée par empreinte. Sont signés l'APK signé, l'APK non signé, la SBOM et `SHA256SUMS`.
- **SBOM** : CycloneDX (tâche `:app:sbom`, phase 1), jointe à chaque publication.
- **Déclenchement** : étiquette `v*`. Droits du jeton limités au job : `contents: write`, `id-token: write`.

## Raisons
- Aucune clé de signature Sigstore à gérer. La vérification lie l'artefact au workflow de ce dépôt, à l'étiquette près.
- **v3.1** : permet de remplacer la clé sans casser les mises à jour, tout en restant compatible avec Android 10 à 12 grâce à la v3.

## Non fait
- **Aucune publication réelle** : il faut d'abord créer la clé et les secrets (étapes du propriétaire du dépôt, `docs/release.md`).
- **Recette F-Droid non vérifiée** par un `fdroid build` local.
