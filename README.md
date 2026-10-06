# Keyra 11.0 — clavier Android AZERTY

Keyra est un clavier Android local, conçu pour le Pixel 9a et GrapheneOS. Son interface s’inspire des captures fournies par l’utilisateur. Le code de l’application est sous licence MIT (`LICENSE`) ; les dictionnaires et données Unicode gardent leurs licences respectives (`app/src/main/assets/licenses`).

## Installer et activer

1. Installe l’APK `Keyra-Pixel-9a-v11.apk` sur le téléphone.
2. Ouvre Keyra et touche **Activer le clavier** pour l’autoriser dans Android.
3. Touche **Choisir le clavier**, puis sélectionne Keyra.
4. Essaie la saisie dans le champ de démonstration.

L’APK fourni est signé avec une clé de développement. Pour une publication durable, il faut le signer avec une clé de publication propre. Le code a été testé sur l’AVD Pixel 9a ; le Pixel 9a physique sous GrapheneOS reste à valider.

## Fonctions

- AZERTY en minuscules par défaut, majuscules temporaires ou verrouillées, appui long pour accents et chiffres de la première rangée.
- Deux pages de symboles, pavé numérique, touche emoji séparée, suppression répétée et prise en charge des appuis de plusieurs doigts.
- Panneau emoji agrandi avec catégories, recherche française, 3 944 séquences Unicode et jusqu’à 18 emoji récents classés localement.
- Correction et suggestions françaises hors ligne : distance d’édition, fréquence, mots personnels et tolérance réglable. La correction s’applique à l’espace ; Retour arrière restaure le mot d’origine. Les propositions sélectionnées insèrent un espace final.
- Traduction hors ligne français ↔ anglais : sélectionne du texte ou place le curseur après une phrase, ouvre la troisième page des fonctions, touche **Traduction hors ligne**, puis **Remplacer**. La traduction est calculée localement et remplace le texte source seulement s’il n’a pas changé entre-temps.
- Thèmes colorés, couleurs dynamiques Android, deux couleurs personnalisables et image de fond avec flou réglable séparément pour le fond et les touches.
- Historique local des 20 dernières copies textuelles observées pendant l’affichage du clavier, insertion d’un toucher et effacement dans les réglages.
- Retour haptique désactivable, modes une main, réglage de hauteur, page Confidentialité et notes de version.

Le traducteur embarqué est un lexique léger de mots et d’expressions courantes. Il fonctionne sans téléchargement ni connexion, mais ne couvre pas la grammaire ni la diversité d’un grand modèle de traduction. Les mots inconnus restent inchangés. Le correcteur traite principalement l’orthographe ; sa couverture et ses prédictions ne sont pas équivalentes à celles de Gboard.

## Confidentialité

Keyra ne déclare pas de permission Internet, ne crée pas de compte et ne transmet pas le texte saisi. Les préférences, mots appris, emoji récents et copies sont dans les données privées de l’application. La sauvegarde Android de ces données est désactivée. Les champs de mot de passe et les copies marquées sensibles par l’application source sont exclus de l’historique et des suggestions. L’historique n’est pas chiffré séparément du stockage privé de l’application : il faut verrouiller le téléphone pour protéger les données locales.

Le dictionnaire français vient de [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0) et les noms français des emoji des [annotations Unicode CLDR](https://github.com/unicode-org/cldr-json). Les notices sont dans `app/src/main/assets/licenses`.

## Compiler et vérifier

Ouvre le dossier `D:\Projet CLavier` dans Android Studio. Avec le SDK Android installé :

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

L’APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`. Sur un émulateur Pixel 9a démarré, les tests Android sont exécutables avec `:app:connectedDebugAndroidTest` ou en installant l’APK de test et en lançant `androidx.test.runner.AndroidJUnitRunner`. La dernière vérification directe dans l’AVD a passé 11 tests ; `vnir ` a été remplacé par `venir ` et `bonjour` par `hello` dans le panneau de traduction.
