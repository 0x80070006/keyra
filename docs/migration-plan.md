# Plan de migration — de Keyra 11.0 à Keyra 12.0

> Phase 0. L'arborescence cible est indicative : on la confirme ou on l'ajuste au début de chaque phase. Règle : **un commit par sous-étape**, et les tests restent verts à chaque commit.

## 1. Arborescence cible

```
app/src/main/java/com/example/app_clavier/
  ime/
    KeyraInputService.kt        orchestration seule : cycle de vie, IPC
    InputLogic.kt               délimiteurs, majuscule auto, typographie FR, annulation
    RichInputConnection.kt      cache borné du texte, composing
    Settings.kt                 instantané immuable des préférences
  keyboard/
    KeyboardLayout.kt           dispositions déclaratives (AZERTY, symboles, pavé numérique)
    KeyboardView.kt             une vue Canvas et ExploreByTouchHelper
    KeyDetector.kt              touche la plus proche, grille, zones dynamiques
    PointerTracker.kt           un par doigt
    KeyRenderer.kt              Paint, Path et icônes précalculés
    SuggestionStripView.kt
    PinPad.kt                   pavé PIN mélangé
    feedback/Haptics.kt, feedback/Sound.kt
  engine/
    BinaryDictionary.kt         dictionnaire mappé en mémoire
    SpatialModel.kt             voisinage AZERTY, gaussiennes, modèle personnel
    Suggest.kt                  candidats, score, décision de correction
    NextWord.kt                 bigrammes embarqués et appris
  security/
    SecurityPolicy.kt           règles par type de champ, drapeaux, verrouillage
    LearningGate.kt             seul accès à l'apprentissage
    SecretDetector.kt           Luhn, IBAN, OTP, entropie, préfixes de clés
    IncognitoApps.kt            liste incognito par application
    Panic.kt                    crypto-shredding
  storage/
    KeyManager.kt               KEK (Keystore) et DEK (mémoire)
    EncryptedStore.kt           AtomicFile, journal, AES-GCM
    Migration11to12.kt          import puis suppression des données en clair
    UserWords.kt, Bigrams.kt, TouchModel.kt, ClipboardVault.kt, Snippets.kt
  panels/                       emoji, presse-papiers, traduction, thèmes, menu
  ui/                           SettingsActivity, TransparencyActivity, VaultUnlockActivity, ExportActivity
  Tracing.kt, InputLatency.kt   diagnostic (phase 0)
buildSrc/ (ou tools/)           DictionaryCompiler, règle de lint maison
docs/                           threat-model, architecture, adr/, masvs-audit, allowed-permissions.txt
benchmark/                      Macrobenchmark et Baseline Profile
```

## 2. Devenir de chaque fichier existant

| Fichier 11.0 | Devient | Phase | Remarque |
|--------------|---------|-------|----------|
| `MintInputService.kt` | `ime/KeyraInputService.kt` + `ime/InputLogic.kt` + `ime/RichInputConnection.kt` | 4 | Changer le nom du service oblige l'utilisateur à réactiver le clavier. On peut garder le nom de classe `MintInputService` dans le manifeste et déléguer : à trancher en phase 4. |
| `MintKeyboard.kt`, partie lettres et symboles (`buildKeys`, `bottom`, `buildNumpad`) | `keyboard/KeyboardLayout.kt` + `KeyboardView.kt` + `KeyDetector.kt` + `PointerTracker.kt` + `KeyRenderer.kt` | 2 | La classe `Key` disparaît. Les `contentDescription` sont conservées via `ExploreByTouchHelper`. |
| `MintKeyboard.kt`, panneaux (`buildEmoji`, `buildPanel`) | `panels/*.kt` | 2 | Restent en vues classiques, créés à la première ouverture puis gardés en cache. |
| `MintKeyboard.kt`, bandeau (`toolbar`, `applySuggestions`) | `keyboard/SuggestionStripView.kt` | 2, puis 5 | Bandeau à la SwiftKey en phase 5. |
| `FrenchCorrector.kt` | `engine/BinaryDictionary.kt` + `engine/Suggest.kt` + `engine/SpatialModel.kt` | 5 | `CorrectionQualityTest` reste la référence : le plancher ne doit jamais baisser. |
| `UserLexicon.kt` | `storage/UserWords.kt` (via `LearningGate`) | 3 | Migration des données JSON en clair, puis suppression. |
| `ClipboardHistory.kt` | `storage/ClipboardVault.kt` | 3 | Chiffré, avec expiration. |
| `EmojiHistory.kt` | stockage chiffré (`storage/`) | 3 | — |
| `KeyboardPrefs.kt` | `ime/Settings.kt` (lecture) + palettes conservées | 4 | Les préférences d'apparence restent dans les SharedPreferences : elles ne sont pas sensibles. |
| `ThemeBackground.kt` | inchangé | — | Ajouter une taille maximale d'image (menace D-4). |
| `EmojiCatalog.kt` | `panels/emoji/` | 2 | Chargé à la première ouverture du panneau emoji. |
| `OfflineTranslator.kt` | inchangé | — | Coupé selon `SecurityPolicy`. |
| `MediaInputActivity.kt` | inchangé, plus un avertissement et `PendingInput` lié à la session | 1 | — |
| `SettingsActivity.kt` | `ui/SettingsActivity.kt` avec `FLAG_SECURE` | 1 | — |
| `MainActivity.kt` | inchangé | — | Champ d'essai utilisé par le benchmark. |
| `InputLatency.kt`, `Tracing.kt` | inchangés | 0 | — |
| `ui/theme/*.kt` | supprimés | 1 | Modèle Compose inutilisé (ADR-0016). |
| `res/xml/data_extraction_rules.xml`, `backup_rules.xml` | réécrits avec exclusion totale, référencés dans le manifeste | 1 | Menace I-6. |

## 3. Ordre et points d'arrêt

1. **Phase 1**, sans risque fonctionnel : manifeste, sauvegardes, `FLAG_SECURE`, `SecurityPolicy`, retrait des dépendances, R8, CI.
2. **Phase 2**, la plus risquée pour l'expérience : remplacer d'abord la *détection* (`KeyDetector` derrière les vues actuelles, qui supprime les zones mortes tout de suite), *puis* le rendu. Les deux changements sont mesurables séparément.
3. **Phase 3**, avant tout nouvel apprentissage : le stockage chiffré doit exister avant les bigrammes et le modèle de toucher.
4. **Phases 4 et 5**, logique de saisie puis moteur, toujours comparés à `CorrectionQualityTest`.
5. **Phases 6 et 7.**

Après chaque phase : rapport, mesures `PERF.md`, arrêt pour validation.
