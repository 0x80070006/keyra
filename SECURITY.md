# Politique de sécurité de Keyra

## Signaler une vulnérabilité

**N'ouvre pas de ticket public.** Utilise le signalement privé de GitHub : onglet *Security*, puis *Report a vulnerability* ([lien direct](https://github.com/0x80070006/keyra/security/advisories/new)). Le rapport reste visible des seuls mainteneurs.

Indique si possible :
- la version (Réglages → Notes de version) et l'appareil, Android ou GrapheneOS ;
- les étapes pour reproduire, et ce qu'un attaquant obtient ;
- **jamais de vrai mot de passe ni de texte personnel** dans le rapport ou les captures.

**Chiffrement du rapport.** La messagerie privée de GitHub est chiffrée en transit. Le projet n'a pas encore de clé PGP publiée. Si tu as besoin d'un canal chiffré de bout en bout, demande-le dans le signalement privé : une clé sera publiée ici et dans les notes de version, avec son empreinte.

## Délais

| Étape | Délai visé |
|-------|------------|
| Accusé de réception | 3 jours ouvrés |
| Première évaluation (gravité, versions touchées) | 10 jours |
| Correctif d'une faille grave (fuite du texte tapé, contournement du chiffrement) | 30 jours |
| Publication de l'avis | à la sortie du correctif, ou 90 jours après le signalement au plus tard, en accord avec toi |

Keyra est maintenu bénévolement : ces délais sont un engagement de moyens. Tu seras crédité dans l'avis, si tu le souhaites.

## Périmètre
- **Dans le périmètre** : l'application, le cœur Rust (`rust/`), la chaîne de build et de publication (`.github/workflows`).
- **Hors périmètre** :
  - les services de reconnaissance vocale tiers (Keyra les signale avant usage) ;
  - les failles d'Android lui-même ;
  - les attaques qui supposent un téléphone déverrouillé et rooté par l'attaquant.

Les hypothèses de sécurité sont décrites dans `docs/threat-model.md`, et l'auto-audit MASVS dans `docs/masvs-audit.md`.

## Versions prises en charge
Seule la dernière version publiée reçoit des correctifs.

## Vérifier une publication
Chaque APK publié est signé (v2 et v3) et accompagné d'un bundle Sigstore. Voir `docs/release.md`.
