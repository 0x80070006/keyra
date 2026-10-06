# Modèle de menace — Keyra

> Phase 0, version du 2026-10-06, établie sur Keyra 11.0 (commit `fa164cc`).
> Méthode : STRIDE appliqué à chaque frontière de confiance du diagramme `docs/architecture.md`.
> Échelle de gravité : **Critique** (fuite de secrets sans action de l'utilisateur), **Haute**, **Moyenne**, **Basse**.

## 1. Ce que l'on protège

| Actif | Exemples | Où il vit aujourd'hui |
|-------|----------|-----------------------|
| **Texte tapé** | messages, recherches, codes, mots de passe | mémoire du processus IME, application cible |
| **Secrets tapés hors champ mot de passe** | mot de passe dans un champ texte, code 2FA, numéro de carte, IBAN, clé d'API | mots appris (`UserLexicon`), mémoire |
| **Vocabulaire appris** | mots inconnus validés, mots personnels | SharedPreferences `keyboard`, **en clair** |
| **Historique du presse-papiers** | 20 dernières copies texte | SharedPreferences `clipboard_history`, **en clair** |
| **Habitudes** | emoji fréquents, horodatages | SharedPreferences `emoji_history`, **en clair** |
| **Intégrité du logiciel** | APK, mises à jour, dépendances | APK signé avec une **clé de développement** |
| **Disponibilité** | pouvoir taper à tout moment | service IME |

## 2. Acteurs hostiles retenus

| Acteur | Capacités supposées | Hors périmètre |
|--------|--------------------|----------------|
| **A1 — Réseau distant** | observe ou reçoit tout trafic émis | — |
| **A2 — Dépendance ou outil de build compromis** | exécute du code au build ou dans l'APK | compromission du SDK Android lui-même |
| **A3 — Personne proche ou voleur** | voit l'écran, tient un appareil **déverrouillé**, peut brancher un câble ADB si le débogage est actif | extraction matérielle sur appareil verrouillé (protégée par le chiffrement de GrapheneOS et la puce Titan M2) |
| **A4 — Application malveillante locale** | sans privilège système, peut être au premier plan, peut recevoir du texte de Keyra | exploit noyau ou root |
| **A5 — Application cible hostile** | reçoit l'`InputConnection`, contrôle `EditorInfo` et les réponses aux lectures de texte | — |
| **A6 — Mainteneur ou compte compromis** | pousse du code ou une mise à jour signée | — |
| **A7 — Personne qui accède à une sauvegarde** | lit une sauvegarde cloud ou un transfert vers un nouvel appareil | — |

## 3. Analyse STRIDE

Colonnes : menace, contre-mesure prévue, phase, risque résiduel.

### S — Usurpation (Spoofing)

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| S-1 | Une application cible déclare un `EditorInfo` mensonger : champ « texte » alors qu'elle demande un mot de passe. | Le détecteur de secrets s'applique à **tous** les champs. On n'apprend jamais un mot contenant un chiffre. | 3 | Un mot de passe composé de lettres seules et peu entropique peut être appris. Il reste visible et effaçable dans le tableau de transparence (phase 6). |
| S-2 | Une application se fait passer pour une application incognito, par exemple une banque, en falsifiant `EditorInfo.packageName`. | Le système vérifie que `packageName` appartient à l'UID appelant. **À confirmer par un test instrumenté.** | 3 | Usurper ce nom ne ferait que *désactiver* l'apprentissage : sans intérêt pour un attaquant. |
| S-3 | Faux APK « Keyra » distribué hors des canaux officiels. | Signature de release dédiée avec rotation (v3.1), empreinte publiée, builds reproductibles, publication F-Droid. | 7 | L'utilisateur installe un APK sans vérifier sa provenance. |
| S-4 | Le résultat d'une dictée ou d'une image (`PendingInput`) est injecté dans un **autre** champ que celui d'origine. | Jeton de session, délai de 60 s, vérification de `packageName` et de `fieldId`. | 1 | Faible. |

### T — Altération (Tampering)

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| T-1 | Dépendance compromise qui ajoute du code ou la permission `INTERNET` au manifeste fusionné. | Viser **zéro dépendance d'exécution**. `verification-metadata.xml` avec sommes SHA-256. La CI vérifie le manifeste fusionné. SBOM. | 1 | Un outil de build compromis (AGP, Kotlin) n'est détectable que par la comparaison de deux builds reproductibles. |
| T-2 | JDK ou outil récupéré au build sans vérification (plugin `foojay-resolver`). | Retirer ce plugin, utiliser un JDK installé et documenté. Le wrapper Gradle est déjà épinglé par SHA-256 : on le garde. | 1 | Confiance dans la distribution du JDK. |
| T-3 | Modification des données stockées (mots appris, presse-papiers) pour injecter des suggestions trompeuses. | AES-GCM authentifié : un fichier modifié est rejeté. Données associées = type, version et nom du fichier. | 3 | Nécessite déjà le root. Le rejet fait perdre les données, sans risque d'exécution. |
| T-4 | Mise à jour malveillante signée par un compte compromis. | Commits signés, branche protégée, revue obligatoire, deux builds reproductibles comparés, artefacts signés avec cosign. | 7 | Compromission conjointe du mainteneur et de sa clé de signature. |
| T-5 | Fichier de stockage tronqué par l'arrêt forcé du processus pendant une écriture. | `AtomicFile` pour la photo complète, journal en ajout seul dont l'enregistrement final tronqué est ignoré. | 3 | Perte de la dernière écriture non terminée. |

### R — Répudiation

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| R-1 | Impossible d'établir qui a introduit un changement dans le code ou la release. | Commits signés, revue obligatoire, `SECURITY.md`, releases signées avec Sigstore. | 7 | Aucun pour l'utilisateur final : Keyra n'a ni compte ni serveur. |

### I — Divulgation d'information (la catégorie principale)

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| I-1 | **Exfiltration réseau** du texte tapé. | Aucune permission `INTERNET`, vérifiée par la CI sur le manifeste fusionné et par un test instrumenté qui existe déjà. Aucune dépendance réseau. | déjà fait, durci en 1 | Canaux auxiliaires sans permission : intents vers d'autres applications, presse-papiers. Atténués : aucun intent ne transporte de texte tapé, sauf la dictée (I-9). |
| I-2 | **Apprentissage en navigation privée** : `IME_FLAG_NO_PERSONALIZED_LEARNING` est ignoré (constat S1). | `SecurityPolicy` lit ce drapeau. `LearningGate` est le seul accès à l'apprentissage. | 1 et 3 | Aucun. |
| I-3 | **Apprentissage de secrets** tapés hors champ mot de passe (constat S2). | `SecretDetector` : Luhn, IBAN, OTP, entropie, préfixes de clés connus, aucun mot contenant un chiffre. | 3 | Mot de passe composé uniquement de lettres ressemblant à un mot. Atténué par le tableau de transparence et le geste panique. |
| I-4 | **Presse-papiers conservé en clair et sans limite** : un mot de passe copié depuis un gestionnaire qui ne pose pas `EXTRA_IS_SENSITIVE` reste stocké (constat S3). | Historique chiffré et désactivable, expiration (1 h par défaut, 30 s pour une copie sensible), copie sensible jamais écrite sur disque, rien depuis l'écran de verrouillage. | 3 | La copie existe dans le presse-papiers *système* tant qu'elle n'est pas remplacée ; elle est en dehors du contrôle de Keyra, sauf avec l'option d'effacement. |
| I-5 | **Données au repos en clair** : mots appris, mots personnels, emoji (constat S4). Lisibles avec un accès root, une extraction forensique sur appareil déverrouillé ou une sauvegarde. | Chiffrement par enveloppe : clé maître dans le Keystore (StrongBox), clé de données en mémoire effacée à l'extinction de l'écran. | 3 | Clé de données en mémoire du processus tant que l'appareil est déverrouillé (ADR-0006). |
| I-6 | **Transfert d'appareil à appareil** : depuis Android 12, `allowBackup="false"` ne le désactive plus (constat S5). | `dataExtractionRules` avec exclusion totale des sauvegardes cloud et des transferts, et `fullBackupContent` pour les versions antérieures. | 1 | Aucun une fois les règles référencées. Données chiffrées de toute façon en phase 3. |
| I-7 | **Captures d'écran** des réglages, qui affichent les mots personnels, dans la vue des applications récentes (constat S6). | `FLAG_SECURE` sur toute activité affichant des données apprises. Étudier son effet sur la fenêtre de l'IME quand un panneau de données est ouvert. | 1 et 6 | Le comportement de `FLAG_SECURE` sur la fenêtre d'un IME **reste à vérifier sur appareil**. |
| I-8 | **Journaux** : texte tapé dans logcat, les traces Perfetto, les messages d'exception, ou dans un rapport de bug (`adb bugreport`, qui contient logcat et `dumpsys input_method`). | Aucun `Log` avec une variable dans les paquets sensibles (règle de lint maison). R8 supprime `android.util.Log` en release. Noms de sections `Trace` constants (déjà fait en phase 0, voir `Tracing.kt`). Ne pas surcharger `InputMethodService.dump`. | 0, 1 et 7 | `dumpsys input_method` affiche l'`EditorInfo` du champ courant (type, paquet), pas son texte. **À vérifier.** |
| I-9 | **Dictée** : `RecognizerIntent` envoie l'audio au service de reconnaissance installé, qui peut fonctionner en ligne (constat S7). | Avertissement avant le premier usage. Dictée coupée en champ sensible et en incognito. Modèle Whisper hors ligne reporté (application compagnon). | 1 | L'utilisateur qui accepte l'avertissement accepte la politique du service installé. |
| I-10 | **Regard par-dessus l'épaule** : surbrillance de la touche, bulle d'aperçu, son de touche (canal acoustique). | Coupés en champ sensible, en incognito et sur le pavé PIN. Pavé PIN mélangé en option. | 2 et 6 | Le mouvement des doigts reste observable. |
| I-11 | **Application cible hostile** (A5) : elle reçoit le texte qu'on y tape, ce qui est normal, mais peut aussi appeler des fonctions qui lui renvoient du contenu : coller depuis l'historique du presse-papiers, traduction. | Le collage depuis l'historique exige un toucher explicite. La traduction ne lit que la sélection ou la phrase en cours. Les lectures `InputConnection` sont bornées en taille. | 4 | Ce que l'utilisateur colle volontairement. |
| I-12 | **Écran de verrouillage** : le clavier peut s'afficher sur un appareil verrouillé (réponse rapide à une notification, selon la configuration) et montrer le presse-papiers, les extraits de texte ou des suggestions apprises. | `SecurityPolicy.isLocked` (`KeyguardManager`) : pas de presse-papiers, pas d'extraits, pas d'apprentissage. La clé `setUnlockedDeviceRequired` est de toute façon inutilisable. | 1 et 3 | Suggestions tirées du seul dictionnaire embarqué. |
| I-13 | **Service d'accessibilité malveillant** : il lit les événements de clic des touches, qui ont des `contentDescription`. | Hors de portée d'un IME : un service d'accessibilité lit tout l'écran. En champ mot de passe, ne pas annoncer le caractère. | 2 | Accepté : c'est l'utilisateur qui accorde ce privilège. |
| I-14 | **Copies résiduelles en mémoire** du texte (objets `String` de la JVM, ADR-0011). | Effacement au mieux des clés et tampons déchiffrés. Pas de cache durable du texte, effacement des champs au changement de session. | 3 et 4 | Copies en attente du ramasse-miettes. Lisibles seulement avec root ou débogueur, ce qui suppose un build débogable. |

### D — Déni de service

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| D-1 | Une application cible renvoie des réponses énormes ou lentes à `getTextBeforeCursor`, ce qui bloque le thread principal. | Cache local (`RichInputConnection`) et lectures rares et bornées, avec troncature défensive du résultat. | 4 | Une lecture lente au début de la session. |
| D-2 | Fichier de stockage corrompu, ce qui fait planter le clavier en boucle. | Parseurs stricts, bornés et fuzzés. En cas d'échec, on démarre à vide et on signale le problème, sans exception non rattrapée. | 3 | Perte des données apprises. |
| D-3 | Pression mémoire et arrêt du processus pendant la frappe. | Dictionnaire mappé en mémoire, sans tas JVM ; `onTrimMemory` ; écritures atomiques. | 3 et 5 | Redémarrage du service, qui doit être rapide (≤ 150 ms). |
| D-4 | Image de thème piégée ou énorme. | Décodage échantillonné (déjà fait), limite de taille, décodage hors du thread principal (déjà fait). | 1 | Faille du décodeur de la plateforme. |

### E — Élévation de privilèges

| # | Menace | Contre-mesure | Phase | Résiduel |
|---|--------|---------------|-------|----------|
| E-1 | Composant exporté détourné par une autre application. | Dans le code de Keyra, seuls `MainActivity` (lanceur) et le service IME (protégé par `BIND_INPUT_METHOD`, réservé au système) sont exportés. **Mais le manifeste de release fusionné de 11.0 contient aussi des composants de bibliothèques** : `androidx.profileinstaller.ProfileInstallReceiver` (**exporté**, protégé par `android.permission.DUMP`) et `androidx.startup.InitializationProvider` (non exporté ; il exécute `EmojiCompatInitializer` au démarrage du processus du clavier). Ils disparaissent avec le retrait des dépendances (ADR-0014 à 0016). Ensuite, la CI compare la liste des composants du manifeste fusionné à une liste blanche. | 1 | Aucun après la phase 1. |
| E-2 | Exécution de code via des données : parseurs, désérialisation. | Kotlin sûr en mémoire, pas de JNI (ADR-0001), pas de réflexion, pas de chargement dynamique, pas de WebView, parseurs fuzzés. | 1 à 6 | Bogue dans une bibliothèque de la plateforme. |
| E-3 | Le privilège de l'IME (lire le presse-papiers à tout moment en tant qu'IME par défaut, Android 10+) est réutilisé par du code tiers. Aujourd'hui, du code tiers s'exécute déjà au démarrage du processus via `androidx.startup`. | Zéro dépendance d'exécution (ADR-0013 à 0016). | 1 | Aucun. |

## 4. Correspondance avec les constats de l'audit

| Constat | Menace(s) | Gravité | Phase |
|---------|-----------|---------|-------|
| S1 Navigation privée apprise | I-2 | Haute | 1 |
| S2 Secrets appris | I-3, S-1 | Haute | 3 |
| S3 Presse-papiers en clair | I-4 | Haute | 3 |
| S4 Données au repos en clair | I-5 | Moyenne | 3 |
| S5 Transfert d'appareil | I-6 | Moyenne | 1 |
| S6 Réglages sans `FLAG_SECURE` | I-7 | Moyenne | 1 |
| S7 Dictée en ligne possible | I-9 | Moyenne | 1 |
| S8 Clé de développement, chaîne d'approvisionnement | S-3, T-1, T-2, T-4 | Moyenne | 1 et 7 |
| S9 `PendingInput` global | S-4 | Basse | 1 |
| S10 Écran de verrouillage | I-12 | Moyenne | 1 et 3 |

## 5. Hypothèses à vérifier sur appareil

1. `EditorInfo.packageName` est vérifié par le système (S-2).
2. Effet de `FLAG_SECURE` posé sur la fenêtre de l'IME (I-7).
3. Contenu de `dumpsys input_method` pendant une saisie (I-8).
4. Affichage de Keyra sur l'écran de verrouillage de GrapheneOS, et dans quels cas (I-12).
5. Disponibilité de StrongBox pour AES-256-GCM sur le Pixel 9a (ADR-0006). Prévoir le repli sur le TEE.

## 6. Avancement (fin de phase 1)

| Menace | État | Où |
|--------|------|----|
| I-2 Navigation privée apprise (S1) | **corrigée** | `SecurityPolicy`, `SecurityPolicyTest` |
| I-3 Secrets appris (S2) | **atténuée** : `SecretDetector` (Rust) avant tout apprentissage ; tableau de transparence en phase 6 | `MintInputService`, `keyra-core::secret` |
| I-4 Presse-papiers (S3) | **atténuée** : aucune copie secrète conservée ; chiffrement et expiration en phase 3 | `ClipboardHistory` |
| I-5 Données au repos en clair (S4) | ouverte (phase 3) | — |
| I-6 Transfert d'appareil (S5) | **corrigée** | `data_extraction_rules.xml` |
| I-7 Captures des réglages (S6) | **corrigée** pour les réglages ; fenêtre de l'IME à étudier en phase 6 | `SettingsActivity` |
| I-8 Journaux | **corrigée** : `logGuard`, clippy, R8 | `app/build.gradle.kts`, `rules.keep` |
| I-9 Dictée (S7) | **atténuée** : avertissement nommant le service | `MediaInputActivity` |
| I-12 Écran de verrouillage (S10) | **corrigée** pour l'apprentissage et l'historique | `SecurityPolicy.isLocked` |
| S-4 `PendingInput` (S9) | **corrigée** | `PendingInput`, `applyPending` |
| T-1 Dépendance compromise | **atténuée** : zéro dépendance JVM d'exécution, `verification-metadata.xml`, `cargo-deny`, `--locked`, manifeste contrôlé en CI | CI |
| T-2 JDK téléchargé | **corrigée** | ADR-0019 |
| T-4 Mise à jour malveillante | en cours : build reproductible vérifié en CI ; signature en phase 7 | `ci.yml` |
| E-1 et E-3 Composants tiers exportés | **corrigées** : plus aucun composant tiers dans le manifeste | `allowed-components.txt` |
| E-2 Exécution de code via données | **nouvelle surface** : pont JNI (3 blocs `unsafe` audités, tests instrumentés, fuzzing du cœur) | ADR-0021 |

## 7. Avancement (fin de phase 2)

| Menace | État |
|--------|------|
| I-10 Regard par-dessus l'épaule | **atténuée** : pas de surbrillance en champ sensible ni sur le pavé PIN ; pavé PIN mélangé (option) ; zones dynamiques coupées en champ sensible |
| I-13 Service d'accessibilité | inchangée (inhérente) ; caractères annoncés « Point » en champ mot de passe |
| D-1 Application cible lente | inchangée (phase 4, `RichInputConnection`) |
