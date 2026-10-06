# ADR-0014 — Dépendance retirée : androidx.core:core-ktx

- **Statut** : accepté (phase 0), retrait en phase 1
- **Coordonnées** : `androidx.core:core-ktx:1.19.0`

## Contexte
Dépendance ajoutée par le modèle de projet d'Android Studio. Une recherche dans `app/src/main/java` ne trouve **aucun import `androidx.core`**.

## Décision
Retrait en phase 1. Si une API de compatibilité devient nécessaire plus tard, on préfère d'abord l'API de la plateforme : `minSdk 29` en couvre l'essentiel (ADR-0004). Toute réintroduction exige un nouvel ADR.

## Considérations de sécurité
Moins de code tiers dans le processus IME, et `verification-metadata.xml` plus court.
