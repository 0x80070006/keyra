# ADR-0006 — Chiffrement par enveloppe, aucune opération Keystore pendant la frappe

- **Statut** : accepté (phase 0)
- **Arbitrage** : D6

## Contexte
Une opération Android Keystore passe par un appel IPC au service `keystore2`, et coûte plusieurs millisecondes avec StrongBox. Or le budget par frappe est de 2 ms.

## Décision
- **Clé maître (KEK)** : AES-256 dans `AndroidKeyStore`, avec `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, GCM sans remplissage, `setUnlockedDeviceRequired(true)` et `setIsStrongBoxBacked(true)`. Si `StrongBoxUnavailableException` est levée, on se replie sur le TEE.
- **Clé de données (DEK)** : 256 bits tirés par `SecureRandom`, enveloppée par la KEK et stockée dans `keys.bin`.
- La DEK est désenveloppée **une seule fois** après le déverrouillage, puis gardée dans un `ByteArray`. Elle est **remise à zéro** à `ACTION_SCREEN_OFF` et à `onDestroy`.
- Quand l'appareil est verrouillé, rien n'est écrit : les événements autorisés par `LearningGate` attendent en mémoire jusqu'au déverrouillage.

## Conséquences
- Zéro opération Keystore pendant la frappe.
- **Risque résiduel** : la DEK reste en mémoire du processus tant que l'écran est allumé et l'appareil déverrouillé. La lire suppose le root, un débogueur (donc un build débogable) ou une faille du noyau.

## Considérations de sécurité
- Crypto-shredding : le geste panique appelle `KeyStore.deleteEntry(alias)`. La DEK enveloppée devient alors indéchiffrable, même si des copies des fichiers survivent sur la mémoire flash.
- **À vérifier sur le Pixel 9a** : StrongBox prend-il en charge AES-256-GCM ?
