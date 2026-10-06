# ADR-0016 — Dépendances retirées : Jetpack Compose et activity-compose

- **Statut** : accepté (phase 0), retrait en phase 1 (voir aussi ADR-0002)
- **Coordonnées** :
  - `androidx.compose:compose-bom:2026.02.01` et ce qu'il gère : `ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `ui-tooling` et `ui-test-manifest` (debug), `ui-test-junit4` (tests)
  - `androidx.activity:activity-compose:1.13.0`
  - le plugin `org.jetbrains.kotlin.plugin.compose`

## Contexte
Aucun écran n'utilise Compose. Seul le modèle inutilisé `ui/theme/` (Color, Theme, Type) l'importe.

## Décision
En phase 1 : supprimer `ui/theme/`, toutes ces dépendances et le plugin Compose. `ui-test-junit4` ne sert à aucun test existant ; il est retiré lui aussi.

## Conséquences
- Une grande partie du code tiers embarqué disparaît : Compose et ses dépendances transitives forment plusieurs mégaoctets.
- Le build est plus rapide, et la reproductibilité plus simple à obtenir.

## Considérations de sécurité
C'est le plus gros gain de surface d'attaque de la phase 1. **Constaté en phase 0** : le manifeste de release fusionné contient `androidx.startup.InitializationProvider`, qui exécute `EmojiCompatInitializer` au démarrage du processus du clavier, et `androidx.profileinstaller.ProfileInstallReceiver`, **exporté** et protégé par `android.permission.DUMP`. Il contient aussi la permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, ajoutée par `androidx.core`. Après le retrait, il faudra vérifier que ces trois éléments ont disparu.
