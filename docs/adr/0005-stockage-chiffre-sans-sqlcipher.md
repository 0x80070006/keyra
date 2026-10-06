# ADR-0005 — Stockage chiffré par fichiers, sans SQLCipher

- **Statut** : accepté (phase 0)
- **Arbitrage** : D5

## Contexte
Le prompt sécurité cite SQLCipher. Or les volumes sont faibles (au plus quelques dizaines de milliers d'entrées) et Keyra vise zéro dépendance d'exécution.

## Décision
`EncryptedStore` (phase 3) repose sur :
- une **photo complète** des données, écrite par `android.util.AtomicFile` ;
- un **journal en ajout seul**, compacté au-delà de 256 Ko ;
- un chiffrement AES-256-GCM (`javax.crypto`) de chaque enregistrement, avec un nonce aléatoire de 96 bits et comme données associées `type ‖ version ‖ nom de fichier` ;
- des données gardées en mémoire pendant la session, dans des structures simples ;
- un parseur borné en taille et fuzzé avec Jazzer.

## Conséquences
- Pas de requêtes SQL : les recherches se font en mémoire, ce qui suffit pour ces volumes.
- Pas de dépendance native, et un code auditable de quelques centaines de lignes.
- **Risque résiduel** : la taille des fichiers révèle à peu près le volume de données apprises.

## Considérations de sécurité
- Ne jamais réutiliser un nonce avec la même clé. Avec un nonce aléatoire de 96 bits, il faut rester sous 2³² messages par clé ; c'est largement le cas.
- Un enregistrement final tronqué est ignoré. Un enregistrement invalide au milieu du journal arrête le rejeu et le signale, sans faire planter le clavier.
