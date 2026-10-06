# ADR-0026 — Retour haptique et son réglables, permission VIBRATE

- **Statut** : accepté (phase 6).

## Décision
- `keyboard/KeyFeedback` réunit trois modes de vibration :
  - **Aucune**.
  - **Du téléphone** (par défaut) : `KEYBOARD_PRESS` à l'appui et `KEYBOARD_RELEASE` au relâchement validé. Ces constantes suivent le réglage de retour tactile d'Android.
  - **Personnalisée** : primitive `PRIMITIVE_CLICK` d'intensité réglable (API 30), sinon une impulsion de 12 ms d'amplitude réglable. Attributs `USAGE_TOUCH` (API 33).
- **Son** : `AudioManager.playSoundEffect` avec `FX_KEYPRESS_STANDARD`, `SPACEBAR`, `DELETE` ou `RETURN`, et un volume réglable.
- **Bulle d'aperçu** : dessinée dans la vue Canvas au-dessus de la touche, et non dans une `PopupWindow` (écart par rapport au prompt). La rangée du haut a déjà la barre d'outils au-dessus d'elle, dans la même vue : pas de fenêtre supplémentaire, pas d'allocation.
- **Rangée de chiffres** optionnelle. Les rangées de lettres passent alors de 85 à 82 unités.

## Sécurité
- **Son et bulle coupés en champ sensible.** Le son d'Effacer ou d'Entrée et la bulle (visible par-dessus l'épaule) renseignent sur la frappe. La vibration reste au choix de l'utilisateur, comme demandé pour le pavé PIN.
- **Pas de son** quand le téléphone est en silencieux ou en vibreur. Ce mode est relu à chaque ouverture du clavier, pas à chaque frappe.
- **Nouvelle permission `android.permission.VIBRATE`** :
  - permission *normale*, accordée sans invite, qui ne donne accès à aucune donnée ni au réseau ;
  - nécessaire au seul mode « Personnalisée » (`Vibrator.vibrate`) ;
  - ajoutée à `docs/allowed-permissions.txt`, que `tools/check_manifest.py` vérifie en CI.
