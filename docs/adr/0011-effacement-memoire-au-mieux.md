# ADR-0011 — Effacement mémoire « au mieux » sur la JVM

- **Statut** : accepté (phase 0)
- **Arbitrage** : D11

## Décision
- Les clés et les tampons déchiffrés vivent dans des `ByteArray` et `CharArray`. Ils sont remis à zéro (`fill(0)`) dès qu'ils ne servent plus.
- Le texte qui passe par `InputConnection` (`commitText`, `getTextBeforeCursor`) est forcément une `String` ou une `CharSequence` : c'est imposé par Android. On limite donc le nombre de copies et leur durée de vie, avec un cache unique vidé à `onFinishInput`.

## Risque résiduel
Des copies du texte attendent le passage du ramasse-miettes. Pour les lire, il faut le root, un débogueur ou une faille du noyau. Ce texte existe de toute façon dans l'application cible.
