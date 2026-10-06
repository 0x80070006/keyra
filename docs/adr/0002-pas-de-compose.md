# ADR-0002 — Pas de Jetpack Compose

- **Statut** : accepté (phase 0)
- **Arbitrage** : D2

## Contexte
Le prompt sécurité propose une interface en Compose, alors que le prompt réactivité l'exclut de la vue du clavier. Aujourd'hui, Compose est déclaré dans `app/build.gradle.kts` mais **n'est utilisé nulle part**. Seul le modèle `ui/theme/` d'Android Studio y fait appel, et aucun code ne l'appelle.

## Décision
Compose est retiré en phase 1 (dépendances et `ui/theme/`). Le clavier est une vue Canvas unique ; les écrans de réglages restent en vues Android classiques.

## Conséquences
- APK plus léger, démarrage plus rapide, moins de code tiers dans le processus qui voit tout le texte tapé.
- Les écrans de réglages seront moins modernes à écrire. C'est acceptable : ils sont simples.

## Considérations de sécurité
Chaque bibliothèque retirée, c'est du code tiers en moins dans un processus privilégié (voir ADR-0013 à 0016).
