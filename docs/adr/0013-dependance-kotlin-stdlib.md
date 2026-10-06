# ADR-0013 — Dépendance conservée : bibliothèque standard Kotlin

- **Statut** : accepté (phase 0)
- **Coordonnées** : `org.jetbrains.kotlin:kotlin-stdlib`, apportée par le Kotlin intégré à AGP 9 (version 2.2.10, `gradle/libs.versions.toml`)

## Contexte
Tout le code de Keyra est écrit en Kotlin. La bibliothèque standard est donc indispensable à l'exécution.

## Décision
On la conserve. Ce sera **la seule dépendance d'exécution** de Keyra une fois la phase 1 terminée (voir ADR-0014 à 0016). En release, R8 n'en garde que les classes utilisées.

## Considérations de sécurité
- Éditeur : JetBrains, sur Maven Central.
- Somme de contrôle épinglée dans `gradle/verification-metadata.xml` en phase 1.
- Aucun accès réseau, aucun chargement dynamique.
