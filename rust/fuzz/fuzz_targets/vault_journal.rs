//! Le lecteur de journal chiffré ne doit jamais paniquer, quel que soit le fichier lu sur le disque.
#![no_main]

use keyra_core::vault::Vault;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(vault) = Vault::new(&[3; 32]) {
        let journal = vault.read_journal(b"keyra/fuzz", data);
        assert!(journal.records.len() <= data.len() / 44 + 1);
    }
});
