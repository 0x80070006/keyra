//! Export et import chiffrés par phrase de passe (phase 6, ADR-0028).
//!
//! Format (entiers en petit-boutiste) :
//! `magic "KEYRAEXP" (8) ‖ version (1) ‖ sel (16) ‖ mémoire Argon2id en Kio (u32) ‖ itérations (u32)
//! ‖ parallélisme (u32) ‖ nonce (24) ‖ texte chiffré XChaCha20-Poly1305 ‖ étiquette (16)`.
//!
//! - La clé vient d'Argon2id (crate `argon2` de `RustCrypto`, jamais réimplémenté).
//! - Tout l'en-tête est authentifié (données associées) : on ne peut pas baisser les paramètres d'un fichier.
//! - À l'import, des paramètres trop faibles sont refusés **avant** tout calcul (mémoire ≥ 64 Mio,
//!   itérations ≥ 3), comme des paramètres trop lourds (déni de service par un fichier piégé).
//! - Sel et nonce sont fournis par l'appelant (`SecureRandom` côté Kotlin).

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::Zeroizing;

/// Signature du format.
pub const MAGIC: &[u8; 8] = b"KEYRAEXP";
/// Version du format.
pub const VERSION: u8 = 1;
/// Longueur du sel.
pub const SALT_LEN: usize = 16;
/// Longueur du nonce.
pub const NONCE_LEN: usize = 24;
/// Longueur de l'en-tête.
pub const HEADER_LEN: usize = 8 + 1 + SALT_LEN + 12 + NONCE_LEN;
/// Taille maximale d'un fichier d'export (16 Mio).
pub const MAX_SIZE: usize = 16 * 1024 * 1024;
/// Mémoire minimale acceptée (64 Mio).
pub const MIN_MEMORY_KIB: u32 = 64 * 1024;
/// Mémoire maximale acceptée (512 Mio).
pub const MAX_MEMORY_KIB: u32 = 512 * 1024;
/// Itérations minimales acceptées.
pub const MIN_ITERATIONS: u32 = 3;
/// Itérations maximales acceptées.
pub const MAX_ITERATIONS: u32 = 16;
/// Parallélisme maximal accepté.
pub const MAX_LANES: u32 = 4;

/// Paramètres Argon2id utilisés à l'export.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    /// Mémoire en Kio.
    pub memory_kib: u32,
    /// Nombre d'itérations.
    pub iterations: u32,
    /// Parallélisme.
    pub lanes: u32,
}

impl KdfParams {
    /// Paramètres par défaut : 64 Mio, 3 itérations, 1 voie.
    pub const DEFAULT: Self = Self {
        memory_kib: MIN_MEMORY_KIB,
        iterations: MIN_ITERATIONS,
        lanes: 1,
    };

    fn acceptable(self) -> bool {
        (MIN_MEMORY_KIB..=MAX_MEMORY_KIB).contains(&self.memory_kib)
            && (MIN_ITERATIONS..=MAX_ITERATIONS).contains(&self.iterations)
            && (1..=MAX_LANES).contains(&self.lanes)
    }
}

/// Erreurs d'export ou d'import. Elles ne révèlent aucun contenu.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupError {
    /// Ce n'est pas un export Keyra, ou version inconnue.
    NotAnExport,
    /// Fichier tronqué ou trop grand.
    BadSize,
    /// Paramètres Argon2id hors des bornes acceptées.
    WeakOrHeavyParams,
    /// Sel ou nonce de mauvaise longueur, ou phrase de passe vide.
    BadInput,
    /// Mauvaise phrase de passe ou fichier altéré.
    Unauthentic,
}

/// En-tête lu et validé.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    /// Sel Argon2id.
    pub salt: [u8; SALT_LEN],
    /// Paramètres Argon2id.
    pub params: KdfParams,
    /// Nonce `XChaCha20`.
    pub nonce: [u8; NONCE_LEN],
}

fn u32_at(data: &[u8], at: usize) -> Option<u32> {
    let bytes: [u8; 4] = data.get(at..at + 4)?.try_into().ok()?;
    Some(u32::from_le_bytes(bytes))
}

/// Lit et valide l'en-tête, sans aucun calcul coûteux.
///
/// # Errors
/// `NotAnExport`, `BadSize` ou `WeakOrHeavyParams`.
pub fn parse_header(data: &[u8]) -> Result<Header, BackupError> {
    if data.len() > MAX_SIZE {
        return Err(BackupError::BadSize);
    }
    if data.get(..8) != Some(MAGIC.as_slice()) || data.get(8) != Some(&VERSION) {
        return Err(BackupError::NotAnExport);
    }
    if data.len() < HEADER_LEN + 16 {
        return Err(BackupError::BadSize);
    }
    let salt: [u8; SALT_LEN] = data
        .get(9..9 + SALT_LEN)
        .and_then(|s| s.try_into().ok())
        .ok_or(BackupError::BadSize)?;
    let p = 9 + SALT_LEN;
    let params = KdfParams {
        memory_kib: u32_at(data, p).ok_or(BackupError::BadSize)?,
        iterations: u32_at(data, p + 4).ok_or(BackupError::BadSize)?,
        lanes: u32_at(data, p + 8).ok_or(BackupError::BadSize)?,
    };
    if !params.acceptable() {
        return Err(BackupError::WeakOrHeavyParams);
    }
    let nonce: [u8; NONCE_LEN] = data
        .get(p + 12..HEADER_LEN)
        .and_then(|s| s.try_into().ok())
        .ok_or(BackupError::BadSize)?;
    Ok(Header { salt, params, nonce })
}

fn derive(password: &[u8], header: &Header) -> Result<Zeroizing<[u8; 32]>, BackupError> {
    let params = Params::new(
        header.params.memory_kib,
        header.params.iterations,
        header.params.lanes,
        Some(32),
    )
    .map_err(|_| BackupError::WeakOrHeavyParams)?;
    let mut key = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password, &header.salt, key.as_mut_slice())
        .map_err(|_| BackupError::BadInput)?;
    Ok(key)
}

fn encode_header(header: &Header) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN);
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    out.extend_from_slice(&header.salt);
    out.extend_from_slice(&header.params.memory_kib.to_le_bytes());
    out.extend_from_slice(&header.params.iterations.to_le_bytes());
    out.extend_from_slice(&header.params.lanes.to_le_bytes());
    out.extend_from_slice(&header.nonce);
    out
}

/// Chiffre `plaintext` avec une clé dérivée de `password`.
///
/// # Errors
/// `BadInput` (sel, nonce, phrase vide), `WeakOrHeavyParams` ou `BadSize`.
pub fn seal(
    password: &[u8],
    salt: &[u8],
    nonce: &[u8],
    params: KdfParams,
    plaintext: &[u8],
) -> Result<Vec<u8>, BackupError> {
    if password.is_empty() {
        return Err(BackupError::BadInput);
    }
    if !params.acceptable() {
        return Err(BackupError::WeakOrHeavyParams);
    }
    if plaintext.len() > MAX_SIZE - HEADER_LEN - 16 {
        return Err(BackupError::BadSize);
    }
    let header = Header {
        salt: salt.try_into().map_err(|_| BackupError::BadInput)?,
        params,
        nonce: nonce.try_into().map_err(|_| BackupError::BadInput)?,
    };
    let key = derive(password, &header)?;
    let mut out = encode_header(&header);
    let sealed = XChaCha20Poly1305::new(&(*key).into())
        .encrypt(
            &XNonce::from(header.nonce),
            Payload {
                msg: plaintext,
                aad: &out,
            },
        )
        .map_err(|_| BackupError::BadInput)?;
    out.extend_from_slice(&sealed);
    Ok(out)
}

/// Déchiffre un export. Le texte clair est effacé à sa destruction.
///
/// # Errors
/// Voir [`parse_header`] ; `Unauthentic` pour une mauvaise phrase de passe ou un fichier altéré.
pub fn open(password: &[u8], data: &[u8]) -> Result<Zeroizing<Vec<u8>>, BackupError> {
    let header = parse_header(data)?;
    if password.is_empty() {
        return Err(BackupError::BadInput);
    }
    let key = derive(password, &header)?;
    let (aad, sealed) = data.split_at(HEADER_LEN);
    XChaCha20Poly1305::new(&(*key).into())
        .decrypt(&XNonce::from(header.nonce), Payload { msg: sealed, aad })
        .map(Zeroizing::new)
        .map_err(|_| BackupError::Unauthentic)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SALT: [u8; SALT_LEN] = [7; SALT_LEN];
    const NONCE: [u8; NONCE_LEN] = [9; NONCE_LEN];

    fn sealed() -> Vec<u8> {
        seal(b"phrase de passe", &SALT, &NONCE, KdfParams::DEFAULT, b"mots appris").unwrap_or_default()
    }

    #[test]
    fn round_trip_and_wrong_password() {
        let data = sealed();
        assert_eq!(data.len(), HEADER_LEN + 11 + 16);
        assert_eq!(
            open(b"phrase de passe", &data).map(|p| p.to_vec()),
            Ok(b"mots appris".to_vec())
        );
        assert_eq!(open(b"autre phrase", &data).err(), Some(BackupError::Unauthentic));
    }

    #[test]
    fn header_is_authenticated_and_bounded() {
        let data = sealed();
        // Baisser les itérations : refusé avant le calcul.
        let mut weak = data.clone();
        if let Some(b) = weak.get_mut(9 + SALT_LEN + 4) {
            *b = 2;
        }
        assert_eq!(
            open(b"phrase de passe", &weak).err(),
            Some(BackupError::WeakOrHeavyParams)
        );
        // Monter les itérations (acceptable) : l'en-tête authentifié ne correspond plus.
        let mut changed = data.clone();
        if let Some(b) = changed.get_mut(9 + SALT_LEN + 4) {
            *b = 4;
        }
        assert_eq!(open(b"phrase de passe", &changed).err(), Some(BackupError::Unauthentic));
        let mut heavy = data.clone();
        if let Some(b) = heavy.get_mut(9 + SALT_LEN + 3) {
            *b = 0xFF;
        }
        assert_eq!(parse_header(&heavy).err(), Some(BackupError::WeakOrHeavyParams));
        assert_eq!(parse_header(b"KEYRAEXP").err(), Some(BackupError::NotAnExport));
        assert_eq!(
            parse_header(data.get(..HEADER_LEN).unwrap_or_default()).err(),
            Some(BackupError::BadSize)
        );
        assert_eq!(parse_header(b"PK\x03\x04").err(), Some(BackupError::NotAnExport));
    }

    #[test]
    fn rejects_weak_export_parameters_and_bad_inputs() {
        let weak = KdfParams {
            memory_kib: 1024,
            iterations: 3,
            lanes: 1,
        };
        assert_eq!(
            seal(b"p", &SALT, &NONCE, weak, b"x").err(),
            Some(BackupError::WeakOrHeavyParams)
        );
        assert_eq!(
            seal(b"", &SALT, &NONCE, KdfParams::DEFAULT, b"x").err(),
            Some(BackupError::BadInput)
        );
        assert_eq!(
            seal(b"p", &SALT[..4], &NONCE, KdfParams::DEFAULT, b"x").err(),
            Some(BackupError::BadInput)
        );
    }
}
