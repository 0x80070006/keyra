# ADR-0010 — Dictée : avertissement explicite, Whisper hors ligne reporté

- **Statut** : accepté (phase 0)
- **Arbitrage** : D10

## Contexte
`MediaInputActivity` lance `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` avec `EXTRA_PREFER_OFFLINE`. Ce drapeau n'est qu'une **préférence** : l'audio part vers le service de reconnaissance installé, qui peut fonctionner en ligne.

## Décision
- Avant le premier usage, un écran explique que l'audio est confié à l'application de reconnaissance installée, en nommant son paquet si possible.
- La dictée est coupée dans les champs sensibles et en incognito.
- Le modèle Whisper hors ligne est reporté : il suppose une application compagnon qui télécharge le modèle et vérifie son empreinte.

## Considérations de sécurité
Keyra lui-même n'envoie rien. Le risque dépend du service que l'utilisateur a choisi.
