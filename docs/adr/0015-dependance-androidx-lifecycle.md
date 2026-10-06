# ADR-0015 — Dépendance retirée : androidx.lifecycle:lifecycle-runtime-ktx

- **Statut** : accepté (phase 0), retrait en phase 1
- **Coordonnées** : `androidx.lifecycle:lifecycle-runtime-ktx:2.11.0`

## Contexte
Cette dépendance vient du modèle de projet d'Android Studio. Le code ne contient aucun import `androidx.lifecycle`. Les activités héritent de `android.app.Activity`, et le service d'`InputMethodService`.

## Décision
Retrait en phase 1.

## Considérations de sécurité
Elle tire aussi des dépendances transitives (`androidx.arch.core`, `kotlinx-coroutines`…). Les retirer réduit d'autant la surface d'attaque.
