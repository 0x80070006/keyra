# ADR-0001 — Le cœur reste en Kotlin, sans Rust ni JNI

- **Statut** : accepté (phase 0), réévaluable
- **Arbitrage** : D1

## Contexte
Le prompt sécurité demande un cœur en Rust (moteur, parseurs, cryptographie) exposé via UniFFI, pour la sûreté mémoire. Keyra est entièrement écrit en Kotlin.

## Décision
Le moteur, les parseurs et la cryptographie restent en Kotlin. Aucun code natif n'est ajouté.

## Justification
- Kotlin sur ART est **déjà sûr en mémoire** : pas de dépassement de tampon ni d'utilisation après libération dans le code de Keyra.
- Ajouter Rust via JNI crée une **nouvelle frontière** (marshalling, durée de vie des objets) et des bibliothèques `.so` par architecture, plus difficiles à rendre reproductibles et à faire accepter par F-Droid.
- La cryptographie passe par `javax.crypto` et Android Keystore, éprouvés et adossés au matériel. Réimplémenter en Rust n'apporterait pas de garantie supplémentaire.
- Les outils prévus pour Rust ont des équivalents JVM : **Jazzer** (fuzzing), **jqwik** ou Kotest (tests par propriétés), **Kover** (couverture).

## Conséquences
- `zeroize` n'existe pas sur la JVM : l'effacement est fait au mieux sur des `ByteArray` et `CharArray` (ADR-0011).
- Réévaluation obligatoire si (a) les budgets de latence de la phase 5 ne sont pas tenus, ou (b) un moteur de modèle de langage natif est introduit. Rust serait alors préféré au C++.

## Considérations de sécurité
Le risque restant est celui de la plateforme (ART, bibliothèques système), identique avec ou sans Rust.
