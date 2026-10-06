# Keyra 12.0

## Nouveautés
- **Frappe réactive** :
  - une seule vue dessine le clavier ;
  - la touche visée est la plus proche du doigt, sans zone morte ;
  - un second doigt valide aussitôt le premier ;
  - glisser depuis ?123 pour taper un symbole.
- **Correction et prédiction réécrites en Rust**, mesurées sur 200 fautes :
  - 83,5 % de corrections justes (79,5 % en 11.0) et 1,5 % de corrections fausses (4 %) ;
  - 1,5 ms par frappe dans 95 % des cas (33 ms en 11.0).
- **Bandeau à la SwiftKey** : mot tapé, correction en gras, suggestion suivante, emoji. Un appui long masque une suggestion.
- **Vie privée** :
  - stockage chiffré ;
  - presse-papiers éphémère ;
  - incognito par application ;
  - détection des secrets ;
  - geste panique ;
  - pavé PIN mélangé.
- **Tableau de transparence**, **extraits de texte** (protégeables par empreinte), **export et import chiffrés**.
- **Dispositions** BÉPO, QWERTY, QWERTZ et Dvorak ; rangée de chiffres ; vibration, son et bulle d'aperçu réglables.
- **Gestes** : pavé tactile sur Espace, Retour arrière accéléré et glissé, balayages.

## Sécurité et publication
- **Permissions** : deux permissions *normales* seulement, `VIBRATE` et `USE_BIOMETRIC`. Toujours **pas d'accès Internet**.
- **Publication** :
  - APK signé v2 et v3, avec rotation possible (v3.1) ;
  - signature Sigstore ;
  - SBOM CycloneDX ;
  - build reproductible.
- **Audit** : auto-audit OWASP MASVS (`docs/masvs-audit.md`).

## Limites connues
- **Dictée vocale** : elle passe par le service de reconnaissance du téléphone, qui peut être en ligne.
- **Mémoire** : l'effacement est fait au mieux sur la JVM, et la clé de données reste en mémoire tant que le téléphone est déverrouillé.
- **Appareil réel** : pas encore validé sur un Pixel 9a physique sous GrapheneOS.
