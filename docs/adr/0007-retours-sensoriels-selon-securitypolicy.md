# ADR-0007 — Retours visuels et sonores coupés selon `SecurityPolicy`

- **Statut** : accepté (phase 0)
- **Arbitrage** : D7

## Décision
Dans certaines situations, Keyra coupe plusieurs retours. Ces situations sont :
- un champ mot de passe ;
- l'incognito (drapeau `NO_PERSONALIZED_LEARNING` ou application inscrite dans la liste) ;
- le pavé PIN ;
- un appareil verrouillé.

Dans tous ces cas, il n'y a **pas** de bulle d'aperçu, **pas** de zones de toucher dynamiques, **pas** de son de touche, **pas** de surbrillance prolongée après le relâchement et **pas** de mise à jour du modèle de toucher. L'haptique reste au choix de l'utilisateur.

## Justification
La bulle et la surbrillance facilitent l'espionnage par-dessus l'épaule. Le son ouvre un canal acoustique. Les zones dynamiques trahiraient indirectement la prédiction.

## Considérations de sécurité
La décision est centralisée dans `SecurityPolicy` et testée sur la JVM pour chaque combinaison de type de champ et de drapeaux.
