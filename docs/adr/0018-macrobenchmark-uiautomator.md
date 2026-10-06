# ADR-0018 — Dépendances ajoutées : Macrobenchmark et UiAutomator (module `:benchmark`)

- **Statut** : accepté (phase 0)
- **Coordonnées** : `androidx.benchmark:benchmark-macro-junit4:1.5.0`, `androidx.test.uiautomator:uiautomator:2.4.0`

## Contexte
La phase 0 doit mesurer la fluidité de la frappe : images par seconde et images manquées. Il faut une mesure de bout en bout sur l'appareil, avec la vraie fenêtre de l'IME.

## Décision
Ces bibliothèques sont ajoutées **uniquement** au module de test `:benchmark` (plugin `com.android.test`). Ce module cible la variante `benchmark` de l'application, une copie de release signée avec la clé de debug. Elle est déclarée `<profileable android:shell="true"/>` dans `app/src/benchmark/AndroidManifest.xml`.

## Conséquences
- L'APK de release ne contient ni ces bibliothèques ni l'attribut `profileable`.
- En phase 7, le même module générera le Baseline Profile.

## Considérations de sécurité
`profileable shell` permet de profiler l'application via ADB. Il est réservé à la variante `benchmark`, jamais distribuée. La CI de la phase 1 vérifiera son absence dans le manifeste de release.
