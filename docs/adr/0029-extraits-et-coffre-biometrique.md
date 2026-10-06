# ADR-0029 — Extraits de texte et coffre biométrique, permission USE_BIOMETRIC

- **Statut** : accepté (phase 6).

## Décision
**Extraits** (magasin chiffré `snippets`) :
- un raccourci de 2 à 16 lettres ou chiffres, et jusqu'à 4 000 caractères ;
- taper le raccourci propose l'extrait à gauche du bandeau ;
- le toucher remplace le raccourci (`InputLogic.expandSnippet`), sans rien apprendre.

**Disponibilité** :
- indisponibles quand l'appareil est verrouillé (coffre fermé) et dans les champs mot de passe ou sans suggestions (`policy.canSuggest`) ;
- restent proposés en incognito : ce sont des données que l'utilisateur a écrites lui-même, et rien n'est appris.

**Extrait protégé** :
- texte chiffré une seconde fois par une clé AES-256-GCM du Keystore (`SnippetVault`) :
  - `setUserAuthenticationRequired(true)` ;
  - `setUserAuthenticationParameters(30, BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` (API 30 ; durée de validité équivalente en API 29) ;
  - `setUnlockedDeviceRequired(true)` ;
- le bandeau n'en montre que le raccourci (« 🔒 adr »).

**Invite** : l'IME ne peut pas l'afficher de façon fiable. `UnlockActivity`, transparente, non exportée et en `FLAG_SECURE`, affiche `BiometricPrompt`, déchiffre l'extrait et le confie à `PendingInput`. Comme pour la dictée, ce résultat reste lié au champ d'origine et n'est valable que 60 s.

**Geste panique** : la clé `keyra_snippets` est détruite.

## Permission
`android.permission.USE_BIOMETRIC` est une permission *normale*, exigée par `BiometricPrompt`, sans accès réseau ni données. Elle a été ajoutée à `docs/allowed-permissions.txt`.

## Non vérifié
- **Pas de comportement réel sur GrapheneOS** : aucun appareil disponible. À vérifier sur un appareil réel.
- **Pas d'essai de bout en bout du chemin protégé sur l'émulateur.** Il faudrait configurer un code de verrouillage. Le chemin non protégé a été vérifié : « adr », puis toucher l'extrait, donne « 12 rue des Lilas ».
