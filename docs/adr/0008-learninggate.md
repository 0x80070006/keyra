# ADR-0008 — Un seul point d'entrée vers l'apprentissage : `LearningGate`

- **Statut** : accepté (phase 0)
- **Arbitrage** : D8

## Décision
Toute donnée conservée passe par `LearningGate.accept(...)` : mot, bigramme, décalage de toucher, copie, emoji, statistique. Cette fonction vérifie, dans l'ordre :
1. `SecurityPolicy` (mot de passe, `NO_PERSONALIZED_LEARNING`, `NO_SUGGESTIONS`) ;
2. l'incognito par application ;
3. l'appareil verrouillé ;
4. `SecretDetector`.

Aucun autre code ne peut écrire dans `EncryptedStore`.

## Conséquences
Les fonctions inspirées de SwiftKey deviennent acceptables (modèle de toucher, bigrammes, statistiques) : elles sont filtrées, chiffrées, visibles dans le tableau de transparence et effacées par le geste panique. Les statistiques sont désactivées par défaut et ne contiennent que des compteurs.

## Considérations de sécurité
Un test d'architecture vérifie que seuls `LearningGate`, `Panic` et le tableau de transparence appellent `EncryptedStore` en écriture. La visibilité `internal` de Kotlin limite les accès.
