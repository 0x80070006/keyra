# Publier et vérifier une version de Keyra

## 1. Clé de release (une seule fois)
La clé **ne doit jamais entrer dans le dépôt** : `.gitignore` exclut déjà `*.jks` et `*.keystore`.

```bash
keytool -genkeypair -keystore keyra-release.jks -alias keyra -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=Keyra"
```

Garde une copie hors ligne (support chiffré), puis crée ces secrets dans l'environnement GitHub `release` :

| Secret | Contenu |
|--------|---------|
| `KEYRA_KEYSTORE_B64` | `base64 -w0 keyra-release.jks` |
| `KEYRA_KEYSTORE_PASSWORD`, `KEYRA_KEY_PASSWORD` | mots de passe du keystore et de la clé |
| `KEYRA_KEY_ALIAS` | `keyra` |
| `KEYRA_LINEAGE_B64` | facultatif, voir §3 |

Protège l'environnement `release` (approbation requise, étiquettes `v*` seulement).

## 2. Publier
1. Mettre à jour `versionCode` et `versionName` (`app/build.gradle.kts`), `docs/release-notes.md` et `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt`.
2. `git tag -s v12.0 -m "Keyra 12.0" && git push origin v12.0`.
3. Le workflow `.github/workflows/release.yml` :
   - compile sans cache ;
   - vérifie le manifeste ;
   - signe l'APK (v2 et v3 ; v1 désactivée, `minSdk` 29) ;
   - signe l'APK, la SBOM et `SHA256SUMS` avec Sigstore ;
   - crée la publication GitHub.

## 3. Rotation de clé (signature v3.1)
Si la clé doit changer (compromission, algorithme), on crée une **lignée** qui prouve que la nouvelle clé succède à l'ancienne. Les mises à jour restent alors acceptées par les téléphones.

```bash
apksigner rotate --out lineage.bin \
  --old-signer --ks keyra-release.jks --ks-key-alias keyra \
  --new-signer --ks keyra-release-2.jks --ks-key-alias keyra2
```

Ensuite :
- remplacer `KEYRA_KEYSTORE_B64` (et les mots de passe) par la nouvelle clé ;
- mettre `base64 -w0 lineage.bin` dans `KEYRA_LINEAGE_B64`.

Le workflow ajoute alors `--lineage … --rotation-min-sdk-version 33` : la rotation (v3.1) s'applique à partir d'Android 13, et la signature v3 couvre les versions antérieures. Vérification : `apksigner verify --print-certs -v` affiche la lignée.

## 4. Vérifier une publication (utilisateur)
```bash
# Empreintes
sha256sum -c SHA256SUMS
# Signature Sigstore : l'identité doit être le workflow de release de ce dépôt
cosign verify-blob keyra-v12.0.apk --bundle keyra-v12.0.apk.sigstore.json \
  --certificate-identity-regexp '^https://github.com/0x80070006/keyra/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
# Certificat de signature APK
apksigner verify --print-certs keyra-v12.0.apk
```

**Build reproductible** : l'APK non signé joint (`keyra-vX-unsigned.apk`) doit être identique, octet pour octet, à celui que tu compiles depuis l'étiquette avec les outils épinglés (voir `README.md`). La CI vérifie cette propriété à chaque envoi.

## 5. F-Droid
Métadonnées dans `fastlane/metadata/android/fr-FR/`. F-Droid compile depuis l'étiquette. La recette doit installer le NDK et la chaîne Rust épinglés (`rust/rust-toolchain.toml`).

**À faire** : faire vérifier cette recette par un build F-Droid local (`fdroid build`). Ce build n'a pas encore été fait : il demande un hôte Linux avec fdroidserver. Le build reproductible permettra à F-Droid de publier l'APK avec la signature du développeur.
