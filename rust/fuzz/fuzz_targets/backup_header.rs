//! Fuzzing de l'en-tête d'export (ADR-0028) : aucune entrée ne doit paniquer, et un en-tête accepté
//! respecte toujours les bornes Argon2id (jamais de calcul coûteux déclenché par un fichier piégé).
#![no_main]
use keyra_core::backup;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(header) = backup::parse_header(data) {
        assert!(header.params.memory_kib >= backup::MIN_MEMORY_KIB);
        assert!(header.params.memory_kib <= backup::MAX_MEMORY_KIB);
        assert!(header.params.iterations >= backup::MIN_ITERATIONS);
        assert!(header.params.iterations <= backup::MAX_ITERATIONS);
        assert!(header.params.lanes >= 1 && header.params.lanes <= backup::MAX_LANES);
    }
});
