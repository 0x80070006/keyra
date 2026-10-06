# ADR-0020 — Dépendances prévues pour les phases suivantes

- **Statut** : proposé. Chaque dépendance sera **reconfirmée par son propre ADR** au moment de son ajout.

| Dépendance | Phase | Portée | Raison | Alternative écartée |
|------------|-------|--------|--------|---------------------|
| Jazzer (`com.code-intelligence:jazzer-junit`) | 3 | tests | Fuzzing des parseurs (`EncryptedStore`, dictionnaire binaire, dispositions, fichiers d'import) | `cargo-fuzz` : il supposerait du Rust (ADR-0001) |
| jqwik ou Kotest property | 3 | tests | Tests par propriétés (`SecretDetector`, correcteur, `KeyDetector`) | Écrire ces générateurs à la main |
| Kover (plugin Gradle) | 1 | build | Couverture ≥ 90 % sur `engine`, `security` et `storage` | JaCoCo, moins bien intégré à Kotlin |
| detekt (plugin Gradle) | 1 | build | Analyse statique et règle « pas de texte tapé dans les journaux » | Android Lint seul, moins riche pour les règles maison |
| CycloneDX Gradle plugin | 1 | build | Générer la SBOM | — |
| `androidx.profileinstaller` | 7 | **exécution** | Installer le Baseline Profile (frappe compilée dès l'installation) | Rien, et accepter la période de chauffe du JIT |
| ~~Bouncy Castle~~ → crate Rust `argon2` (RustCrypto), voir ADR-0021 | 6 | **exécution** | Argon2id pour l'export chiffré. La plateforme n'en fournit pas. | Réimplémenter Argon2 (interdit) ; PBKDF2 de la plateforme (plus faible face aux GPU) |

## Règle
Une dépendance d'**exécution** n'est acceptée que si (a) la plateforme ne fournit pas l'équivalent, (b) elle ne déclare ni permission ni composant dans son manifeste, et (c) elle est épinglée par une somme de contrôle. Les deux dépendances d'exécution prévues sont à réexaminer au moment venu. Pour Bouncy Castle, envisager de n'embarquer que la classe Argon2 après R8.
