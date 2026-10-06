# Rapport de la phase 2 — Moteur de toucher

> 2026-10-06. Décisions de l'utilisateur : mises à jour AGP 9.4.1 et Gradle 9.8.0 appliquées ; **pas de mesure sur le Pixel 9a**. Les chiffres viennent de l'émulateur Pixel 9a (même définition d'écran : 1080 × 2424).

## 1. Ce qui a été fait

| Élément | Fichier | Inspiration |
|---------|---------|-------------|
| Disposition déclarative (lettres, symboles, pavé numérique, barre d'outils), mêmes coordonnées et descriptions que la 11.0 | `keyboard/KeyboardLayout.kt` | LatinIME (`Keyboard`, `Key`) |
| Détection par proximité, sans zone morte ; boutons isolés sur appui direct ; zones dynamiques prêtes, plafonnées à 25 % | `keyboard/KeyDetector.kt` | LatinIME (`KeyDetector`), SwiftKey (zones dynamiques) |
| Vue Canvas unique | `keyboard/KeyboardView.kt` | LatinIME (`KeyboardView`), FUTO |
| Un suiveur par doigt : retour à l'appui, validation au relâchement, glissement de correction, glissé depuis ?123, phantom up, appui long, répétition d'Effacer | `KeyboardView.Tracker` | LatinIME (`PointerTracker`) |
| Panneau d'accents et de chiffre, dessiné par la même vue, choix sous le doigt | `KeyboardView.Popup` | LatinIME, AnySoftKeyboard |
| Accessibilité par nœuds virtuels (TalkBack), sans AndroidX | `KeyboardView.provider` | `ExploreByTouchHelper`, réécrit sans dépendance |
| Pavé PIN mélangé (option), sans surbrillance | `KeyboardLayouts.numpad`, `MintKeyboard.reset` | fonction innovante n° 5 |
| Maj, modes et suggestions sans reconstruction de vues | `MintKeyboard.refresh` | — |
| Réglages : validation à l'appui, délai d'appui long, pavé PIN mélangé | `SettingsActivity` | FUTO, AnySoftKeyboard |

## 2. Mesures

| Mesure | Phase 0 | Phase 2 | Cible |
|--------|---------|---------|-------|
| Zones mortes, quatre rangées | 27,2 % | **0,0 %** (68 742 points testés) | 0 % ✅ |
| Appuis au milieu des écarts qui tapent | 0 / 9 | **9 / 9** | 9 / 9 ✅ |
| Reconstructions de vues pour « Bonjour Maman » (avec Maj) | 4 | **0** | 0 ✅ |
| Allocations par frappe dans le toucher et le dessin | (une vue par touche, recréée à chaque Maj) | aucune dans `onTouchEvent` et `onDraw` (suiveurs en pool, minuteries créées une fois) | 0 ✅ (revue de code ; à confirmer au profileur sur appareil) |
| Appui → touche dessinée, p95 | — | à mesurer sur Pixel 9a (`InputLatency`) | ≤ 8,3 ms |

La validation au relâchement allonge le délai entre l'appui et l'envoi du caractère de la durée du contact, sans changer celui entre l'appui et l'affichage (ADR-0012). L'ancien comportement reste disponible dans les réglages.

## 3. Tests ajoutés

- **JVM** (`KeyDetectorTest`, 6 tests) :
  - aucun point mort, dans les quatre modes, au pixel de référence près ;
  - touche la plus proche dans les écarts ;
  - boutons isolés sur appui direct ;
  - plafond des zones dynamiques ;
  - majuscules et descriptions ;
  - pavé PIN complet.
- **Instrumentés** (`PerformanceBaselineTest`, `FeatureTest`, avec `KeyboardTestKit` qui envoie de vrais `MotionEvent` à plusieurs doigts) :
  - deux pouces alternés sur 200 frappes, ordre conservé ;
  - glissement de « a » vers « z » ;
  - glissé depuis ?123 vers « 1 », puis retour aux lettres ;
  - appui long sur « e », puis « é » ;
  - répétition d'Effacer ;
  - nœuds d'accessibilité, avec « Point » dans un champ mot de passe ;
  - pavé PIN mélangé ;
  - 200 frappes rapides sans perte ;
  - aucune reconstruction.
- **Vérification visuelle** sur l'émulateur : rendu identique à la 11.0, panneau d'accents correct.

## 4. Considérations de sécurité, fichier par fichier

**`keyboard/KeyboardView.kt`**
- **Surbrillance et panneau** : dans un champ sensible (`quiet`), ni surbrillance pendant l'appui ni éclair après. Le panneau d'accents s'affiche encore en champ sensible si l'on fait un appui long : risque faible, car il montre des choix et non la frappe. À couper en phase 6 si on le juge utile.
- **Zones dynamiques** : coupées en champ sensible. Sinon, la forme des zones trahirait indirectement la prédiction.
- **Accessibilité** :
  - les événements de survol et de clic exposent la description de la touche à tout service d'accessibilité actif. C'est inhérent à l'accessibilité (menace I-13) ;
  - dans un champ mot de passe, les caractères sont annoncés « Point » ;
  - limite : un service d'accessibilité malveillant voit quand même quelle touche est sous le doigt, par sa position.
- **Phantom up** : un second doigt valide la frappe en attente du premier. Le risque, c'est un caractère tapé alors que le premier doigt comptait glisser ailleurs. C'est le comportement de LatinIME : il est accepté.
- **Minuteries** : elles passent par un `Handler` du fil principal ; `cancelAll()` les vide au détachement de la vue. Si une minuterie se déclenchait après le détachement, elle vérifierait `active` et ne ferait rien.

**`keyboard/KeyDetector.kt`**
- Le bonus des zones dynamiques est plafonné et borné à [0, 1]. Une fonction de probabilité défaillante (NaN ou infini) donnerait un bonus nul (`coerceIn`), sans risque de capture d'une touche éloignée.

**`keyboard/KeyboardLayout.kt`**
- Pavé PIN mélangé avec `SecureRandom`, nouveau tirage à chaque `reset`. Le mélange ne protège pas d'une capture vidéo de l'écran, seulement de l'observation des positions des doigts.

**`MintKeyboard.kt`**
- Les panneaux (presse-papiers, traduction, emoji) restent des vues classiques, inchangées. Les fonctions de test `keyRect` et `descriptionAt` ne révèlent que des descriptions de touches, jamais du texte tapé.

**`SettingsActivity.kt`**
- Trois réglages ajoutés. Les libellés sont dans `strings.xml` (Lint strict).

## 5. Reste à faire, hors périmètre de cette phase

- **Pas de bulle d'aperçu** : elle est prévue en phase 6, optionnelle et désactivée par défaut.
- **Barre d'espace en pavé tactile, suppression par mot** : phase 4 (logique de saisie).
- **Zones dynamiques** : la plomberie est prête ; il leur faut les probabilités du moteur, en phase 5.
- **Mesures sur le Pixel 9a** : reportées à la demande de l'utilisateur.
