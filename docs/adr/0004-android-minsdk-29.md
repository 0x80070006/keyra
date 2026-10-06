# ADR-0004 — Android seulement, minSdk 29

- **Statut** : accepté (phase 1, validé par l'utilisateur) (D4)

## Contexte
Keyra cible le Pixel 9a sous GrapheneOS (Android 16), avec `minSdk 24`. Le prompt sécurité vise Android 10 et plus, et/ou iOS.

## Décision proposée
Android seulement. En phase 1, `minSdk` passe de 24 à **29** (Android 10).

## Justification
Ces API deviennent garanties, sans branche de compatibilité :

| API | Niveau | Usage |
|-----|--------|-------|
| `KeyGenParameterSpec.setIsStrongBoxBacked` | 28 | clé maître matérielle |
| `KeyGenParameterSpec.setUnlockedDeviceRequired` | 28 | clé inutilisable quand l'appareil est verrouillé |
| `ClipboardManager.clearPrimaryClip` | 28 | presse-papiers éphémère |
| `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` | 26 | navigation privée |
| Lecture du presse-papiers réservée à l'IME et à l'application au premier plan | 29 | réduit la menace d'une application malveillante locale (A4) |

## Conséquences
Les appareils sous Android 7 à 9 ne sont plus pris en charge. Les icônes PNG classiques deviennent inutiles dès que `minSdk` atteint 26 : elles pourront être supprimées.
