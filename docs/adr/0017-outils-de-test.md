# ADR-0017 — Outils de test conservés (tests seulement)

- **Statut** : accepté (phase 0)

Ces bibliothèques n'entrent **jamais** dans l'APK de release : elles ne vont que dans les APK de test.

| Coordonnées | Usage | Décision |
|-------------|-------|----------|
| `junit:junit:4.13.2` | tests JVM | conservée |
| `androidx.test.ext:junit:1.3.0` | lanceur `AndroidJUnit4`, `ActivityScenario` | conservée |
| `androidx.test.espresso:espresso-core:3.7.0` | apporte `androidx.test.runner` ; Espresso lui-même n'est pas utilisé | à remplacer en phase 1 par `androidx.test:runner` seul |
| `androidx.compose.ui:ui-test-junit4` | aucun usage | retirée (ADR-0016) |

## Considérations de sécurité
Une dépendance de test compromise peut agir sur la machine de build. Elles sont épinglées dans `verification-metadata.xml` comme les autres.
