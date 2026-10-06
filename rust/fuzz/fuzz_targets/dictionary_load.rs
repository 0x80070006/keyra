//! Le chargeur de dictionnaire et la recherche ne doivent jamais paniquer, quel que soit le contenu.
#![no_main]

use keyra_core::predict::Engine;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(text) = std::str::from_utf8(data) {
        let engine = Engine::from_text(text);
        for word in text.split_whitespace().take(4) {
            let _ = engine.analyze(word, 70, 3);
            let _ = engine.next_letters(word);
        }
    }
});
