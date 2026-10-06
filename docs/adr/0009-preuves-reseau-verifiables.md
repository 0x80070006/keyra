# ADR-0009 — Preuves réseau vérifiables au lieu d'un compteur maison

- **Statut** : accepté (phase 0)
- **Arbitrage** : D9

## Décision
Le tableau de transparence n'affiche pas de compteur « octets envoyés : 0 » calculé par Keyra : un tel compteur ne prouverait que la bonne foi du code. Il affiche plutôt deux sources fournies par le système :
1. la liste réelle des permissions demandées, lue via `PackageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions`, avec la mention « INTERNET : absente » ;
2. `TrafficStats.getUidTxBytes(Process.myUid())` et `getUidRxBytes`. Si la valeur vaut `TrafficStats.UNSUPPORTED`, elle est affichée telle quelle.

## Considérations de sécurité
Ces données ne sont pas produites par Keyra. Un utilisateur averti peut les recouper dans les réglages Android : Applications → Keyra → Autorisations, et sur GrapheneOS l'autorisation « Réseau ».
