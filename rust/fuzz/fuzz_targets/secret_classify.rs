//! Le classificateur ne doit jamais paniquer, quelle que soit l'entrée, et il doit rester cohérent :
//! un texte classé secret le reste quand on l'entoure d'espaces.
#![no_main]

use keyra_core::secret::{MAX_INPUT_BYTES, classify};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if data.len() > MAX_INPUT_BYTES {
        return;
    }
    if let Ok(text) = std::str::from_utf8(data) {
        let kind = classify(text);
        assert_eq!(kind, classify(&format!("  {text}\n")));
    }
});
