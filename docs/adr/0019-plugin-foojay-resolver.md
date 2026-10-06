# ADR-0019 — Plugin de build retiré : foojay-resolver-convention

- **Statut** : accepté (phase 0), retrait en phase 1
- **Coordonnées** : `org.gradle.toolchains.foojay-resolver-convention:1.0.0` (`settings.gradle.kts`)

## Contexte
Ce plugin télécharge automatiquement un JDK (via `api.foojay.io`) quand la chaîne d'outils demandée est absente : voir `gradle/gradle-daemon-jvm.properties`, qui vise le JDK 25. Le téléchargement n'est vérifié par aucune somme de contrôle épinglée dans le dépôt.

## Décision
- Retirer le plugin et la résolution automatique.
- Documenter le JDK attendu (JDK 25, par exemple le JetBrains Runtime d'Android Studio) dans le README.
- Faire échouer le build si ce JDK est absent.

## Considérations de sécurité
Ferme une voie d'approvisionnement non vérifiée (menace T-2). Le wrapper Gradle reste épinglé par `distributionSha256Sum`, déjà présent.
