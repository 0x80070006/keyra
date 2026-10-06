# Contribuer à Keyra

Merci ! Keyra est un clavier : il voit tout ce que tape son utilisateur. Les règles ci-dessous protègent cette confiance.

## Règles non négociables
1. **Jamais de permission `INTERNET`**, ni de télémétrie, de compte ou d'analyse d'usage.
2. **Aucun texte tapé** dans les journaux, traces, Toast, exceptions ou messages de test. La tâche `logGuard` le vérifie.
3. **Tout ce qui est conservé passe par `LearningGate`** (vérifié par `ArchitectureTest`), et est chiffré au repos.
4. **En cas d'erreur, on échoue fermé** : moins de fonctions plutôt qu'une fuite.
5. **Pas de code sous licence incompatible.** Code MIT ; Apache-2.0 accepté avec attribution. **Pas de code de FUTO Keyboard** (FUTO Source First License) : ses idées seulement. SwiftKey : comportements seulement.

## Nouvelle dépendance ou permission
**Un ADR est obligatoire** (`docs/adr/`) pour :
- toute nouvelle dépendance (Gradle ou crate Rust) ;
- toute permission ;
- tout composant Android.

L'ADR doit dire :
- pourquoi elle est nécessaire, et quelles alternatives ont été écartées ;
- ce qu'elle ajoute au manifeste et à la surface d'attaque ;
- sa licence.

Puis :
- **Gradle** : mettre à jour `gradle/verification-metadata.xml` (`./gradlew --write-verification-metadata sha256 …`) et relire les empreintes ajoutées.
- **Rust** : `cargo deny check` doit passer.
- **Permission ou composant** : ajouter la ligne dans `docs/allowed-permissions.txt` ou `docs/allowed-components.txt`.

## Branches, commits et revue
- **`main` est protégée** : pas d'envoi direct ni de réécriture d'historique. Tout passe par une pull request.
- **Revue obligatoire** par un mainteneur, et CI verte : Rust, Android, tests instrumentés, build reproductible, CodeQL et Semgrep.
- **Commits signés** (SSH ou GPG) exigés sur `main`.
- Un commit = un changement cohérent, avec un message en français qui dit *pourquoi*.

## Vérifier localement
Voir `README.md`, section « Compiler et vérifier ». En bref :

```bash
./gradlew :app:logGuard :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
```

Tests sur émulateur : `./gradlew :app:connectedDebugAndroidTest`. Sélectionne Keyra comme clavier *après* avoir lancé l'activité de test.

## Signaler une faille
Pas de ticket public : voir `SECURITY.md`.
