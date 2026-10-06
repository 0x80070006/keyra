//! Coffre de Keyra : chiffrement authentifié XChaCha20-Poly1305 des données au repos (ADR-0024).
//!
//! - La clé de données (32 octets) vit uniquement ici, dans une mémoire effacée à la destruction (`Zeroizing`).
//! - Chaque enregistrement scellé = `nonce (24 octets) ‖ texte chiffré ‖ étiquette (16 octets)`.
//!   Le nonce est fourni par l'appelant (`SecureRandom` côté Kotlin) : 192 bits aléatoires, sans risque de collision.
//! - Le `context` est authentifié (données associées) : un fichier d'un magasin ne peut pas être substitué à un autre.
//! - Un journal est une suite de trames `longueur (u32, petit-boutiste) ‖ enregistrement scellé`.
//!   Une trame finale tronquée (écriture interrompue) est ignorée ; une trame altérée arrête la lecture.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::Zeroizing;

/// Longueur de la clé de données.
pub const KEY_LEN: usize = 32;
/// Longueur du nonce `XChaCha20`.
pub const NONCE_LEN: usize = 24;
/// Longueur de l'étiquette d'authentification Poly1305.
pub const TAG_LEN: usize = 16;
/// Taille maximale d'un enregistrement en clair (1 Mio) : borne les allocations.
pub const MAX_RECORD: usize = 1024 * 1024;
/// Taille maximale d'un fichier de journal ou d'instantané lu en une fois (16 Mio).
pub const MAX_FILE: usize = 16 * 1024 * 1024;

/// Erreurs du coffre. Elles ne révèlent jamais de contenu.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VaultError {
    /// Clé de longueur incorrecte.
    BadKey,
    /// Nonce de longueur incorrecte.
    BadNonce,
    /// Entrée trop grande.
    TooLarge,
    /// Authentification impossible : mauvaise clé, mauvais contexte ou données altérées.
    Unauthentic,
}

/// Fin de lecture d'un journal.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JournalEnd {
    /// Toutes les trames ont été lues.
    Complete,
    /// La dernière trame est incomplète (écriture interrompue) : elle est ignorée.
    TruncatedTail,
    /// Une trame est altérée ou trop grande : lecture arrêtée avant elle.
    Corrupted,
}

/// Résultat de la lecture d'un journal : enregistrements en clair, effacés à leur destruction.
pub struct Journal {
    /// Enregistrements déchiffrés, dans l'ordre.
    pub records: Vec<Zeroizing<Vec<u8>>>,
    /// Comment la lecture s'est terminée.
    pub end: JournalEnd,
}

/// Clé de données chargée en mémoire.
pub struct Vault {
    key: Zeroizing<[u8; KEY_LEN]>,
}

impl Vault {
    /// Charge une clé de 32 octets.
    ///
    /// # Errors
    /// `BadKey` si la longueur est incorrecte.
    pub fn new(key: &[u8]) -> Result<Self, VaultError> {
        let bytes: [u8; KEY_LEN] = key.try_into().map_err(|_| VaultError::BadKey)?;
        Ok(Self {
            key: Zeroizing::new(bytes),
        })
    }

    fn cipher(&self) -> XChaCha20Poly1305 {
        XChaCha20Poly1305::new(&(*self.key).into())
    }

    /// Scelle `plaintext` : renvoie `nonce ‖ texte chiffré ‖ étiquette`.
    ///
    /// # Errors
    /// `BadNonce` ou `TooLarge`.
    pub fn seal(&self, context: &[u8], nonce: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, VaultError> {
        let nonce: [u8; NONCE_LEN] = nonce.try_into().map_err(|_| VaultError::BadNonce)?;
        if plaintext.len() > MAX_RECORD {
            return Err(VaultError::TooLarge);
        }
        let sealed = self
            .cipher()
            .encrypt(
                &XNonce::from(nonce),
                Payload {
                    msg: plaintext,
                    aad: context,
                },
            )
            .map_err(|_| VaultError::TooLarge)?;
        let mut out = Vec::with_capacity(NONCE_LEN + sealed.len());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&sealed);
        Ok(out)
    }

    /// Ouvre un enregistrement scellé.
    ///
    /// # Errors
    /// `Unauthentic` si la clé, le contexte ou les données ne correspondent pas.
    pub fn open(&self, context: &[u8], sealed: &[u8]) -> Result<Zeroizing<Vec<u8>>, VaultError> {
        if sealed.len() < NONCE_LEN + TAG_LEN {
            return Err(VaultError::Unauthentic);
        }
        if sealed.len() > MAX_RECORD + NONCE_LEN + TAG_LEN {
            return Err(VaultError::TooLarge);
        }
        let (nonce, body) = sealed.split_at(NONCE_LEN);
        let nonce: [u8; NONCE_LEN] = nonce.try_into().map_err(|_| VaultError::Unauthentic)?;
        self.cipher()
            .decrypt(
                &XNonce::from(nonce),
                Payload {
                    msg: body,
                    aad: context,
                },
            )
            .map(Zeroizing::new)
            .map_err(|_| VaultError::Unauthentic)
    }

    /// Scelle puis encadre un enregistrement pour un journal.
    ///
    /// # Errors
    /// Comme [`Vault::seal`].
    pub fn seal_frame(&self, context: &[u8], nonce: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, VaultError> {
        let sealed = self.seal(context, nonce, plaintext)?;
        let length = u32::try_from(sealed.len()).map_err(|_| VaultError::TooLarge)?;
        let mut out = Vec::with_capacity(4 + sealed.len());
        out.extend_from_slice(&length.to_le_bytes());
        out.extend_from_slice(&sealed);
        Ok(out)
    }

    /// Lit un journal de trames. Ne panique jamais, quelle que soit l'entrée.
    #[must_use]
    pub fn read_journal(&self, context: &[u8], data: &[u8]) -> Journal {
        let mut records = Vec::new();
        if data.len() > MAX_FILE {
            return Journal {
                records,
                end: JournalEnd::Corrupted,
            };
        }
        let mut rest = data;
        loop {
            if rest.is_empty() {
                return Journal {
                    records,
                    end: JournalEnd::Complete,
                };
            }
            let Some((header, tail)) = rest.split_first_chunk::<4>() else {
                return Journal {
                    records,
                    end: JournalEnd::TruncatedTail,
                };
            };
            let length = usize::try_from(u32::from_le_bytes(*header)).unwrap_or(usize::MAX);
            if length > MAX_RECORD + NONCE_LEN + TAG_LEN {
                return Journal {
                    records,
                    end: JournalEnd::Corrupted,
                };
            }
            if tail.len() < length {
                return Journal {
                    records,
                    end: JournalEnd::TruncatedTail,
                };
            }
            let (frame, next) = tail.split_at(length);
            match self.open(context, frame) {
                Ok(plain) => records.push(plain),
                Err(_) => {
                    return Journal {
                        records,
                        end: JournalEnd::Corrupted,
                    };
                }
            }
            rest = next;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    const KEY: [u8; 32] = [7; 32];
    fn nonce(n: u8) -> [u8; NONCE_LEN] {
        [n; NONCE_LEN]
    }

    #[test]
    fn round_trip() {
        let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
        let sealed = vault.seal(b"ctx", &nonce(1), b"bonjour").unwrap_or_default();
        assert_eq!(sealed.len(), NONCE_LEN + 7 + TAG_LEN);
        assert_eq!(vault.open(b"ctx", &sealed).map(|p| p.to_vec()), Ok(b"bonjour".to_vec()));
    }

    #[test]
    fn rejects_wrong_key_context_or_tampering() {
        let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
        let other = Vault::new(&[8; 32]).unwrap_or_else(|_| unreachable!());
        let mut sealed = vault.seal(b"mots", &nonce(2), b"secret").unwrap_or_default();
        assert_eq!(other.open(b"mots", &sealed).err(), Some(VaultError::Unauthentic));
        assert_eq!(
            vault.open(b"presse-papiers", &sealed).err(),
            Some(VaultError::Unauthentic)
        );
        if let Some(byte) = sealed.last_mut() {
            *byte ^= 1;
        }
        assert_eq!(vault.open(b"mots", &sealed).err(), Some(VaultError::Unauthentic));
    }

    #[test]
    fn bad_lengths() {
        assert!(Vault::new(&[0; 31]).is_err());
        let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
        assert_eq!(vault.seal(b"", &[0; 12], b"x").err(), Some(VaultError::BadNonce));
        assert_eq!(vault.open(b"", &[0; 10]).err(), Some(VaultError::Unauthentic));
    }

    #[test]
    fn journal_survives_interrupted_write() {
        let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
        let mut data = Vec::new();
        for (i, word) in ["un", "deux", "trois"].iter().enumerate() {
            data.extend(
                vault
                    .seal_frame(b"j", &nonce(u8::try_from(i).unwrap_or(0)), word.as_bytes())
                    .unwrap_or_default(),
            );
        }
        let full = vault.read_journal(b"j", &data);
        assert_eq!(full.end, JournalEnd::Complete);
        assert_eq!(full.records.len(), 3);
        // Écriture interrompue au milieu de la troisième trame.
        let cut = vault.read_journal(b"j", data.get(..data.len() - 5).unwrap_or_default());
        assert_eq!(cut.end, JournalEnd::TruncatedTail);
        assert_eq!(
            cut.records.iter().map(|r| r.to_vec()).collect::<Vec<_>>(),
            vec![b"un".to_vec(), b"deux".to_vec()]
        );
        // Octet altéré dans la deuxième trame : lecture arrêtée avant elle.
        let mut bad = data.clone();
        if let Some(byte) = bad.get_mut(4 + NONCE_LEN + 2 + TAG_LEN + 4 + NONCE_LEN) {
            *byte ^= 0x80;
        }
        let corrupted = vault.read_journal(b"j", &bad);
        assert_eq!(corrupted.end, JournalEnd::Corrupted);
        assert_eq!(corrupted.records.len(), 1);
    }

    proptest! {
        #[test]
        fn journal_reader_never_panics(data in proptest::collection::vec(any::<u8>(), 0..4096)) {
            let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
            let _ = vault.read_journal(b"j", &data);
        }

        #[test]
        fn any_plaintext_round_trips(plain in proptest::collection::vec(any::<u8>(), 0..2048), n in any::<u8>()) {
            let vault = Vault::new(&KEY).unwrap_or_else(|_| unreachable!());
            let frame = vault.seal_frame(b"p", &nonce(n), &plain).unwrap_or_default();
            let journal = vault.read_journal(b"p", &frame);
            prop_assert_eq!(journal.end, JournalEnd::Complete);
            prop_assert_eq!(journal.records.first().map(|r| r.to_vec()), Some(plain));
        }
    }
}
