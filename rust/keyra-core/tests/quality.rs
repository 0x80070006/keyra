//! Référence de qualité du correcteur, sur les mêmes jeux que la phase 0 (`tools/generate_typos.py`).
//! Phase 0 (`FrenchCorrector`, Kotlin) : 79,5 % corrigé juste, 4,0 % faux, sur-correction hors dictionnaire 4,5 %.
#![allow(
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::doc_markdown,
    clippy::cast_precision_loss,
    missing_docs
)]

use keyra_core::predict::Engine;
use std::path::Path;

fn read(relative: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../").join(relative)).expect(relative)
}

#[test]
fn correction_quality_beats_phase_zero() {
    let started = std::time::Instant::now();
    let engine = Engine::from_text(&read("app/src/main/assets/fr_frequency.txt"));
    let load = started.elapsed();
    let typos: Vec<(String, String, String)> = read("app/src/test/resources/fr_typos.tsv")
        .lines()
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .map(|l| {
            let p: Vec<&str> = l.split('\t').collect();
            (p[0].to_string(), p[1].to_string(), p[2].to_string())
        })
        .collect();
    let oov: Vec<String> = read("app/src/test/resources/fr_oov_valid.tsv")
        .lines()
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .map(String::from)
        .collect();
    let (mut ok, mut wrong, mut top3) = (0, 0, 0);
    let mut by_kind = std::collections::BTreeMap::<String, (u32, u32)>::new();
    let mut timings = Vec::new();
    for (typo, expected, kind) in &typos {
        let t = std::time::Instant::now();
        let (candidates, correction) = engine.analyze(typo, 55, 3);
        timings.push(t.elapsed());
        let entry = by_kind.entry(kind.clone()).or_default();
        entry.1 += 1;
        match correction.as_deref() {
            Some(c) if c == expected => {
                ok += 1;
                entry.0 += 1;
            }
            Some(_) => wrong += 1,
            None => {}
        }
        if candidates.iter().any(|c| &c.word == expected) {
            top3 += 1;
        }
    }
    let over = oov.iter().filter(|w| engine.correction(w, 55).is_some()).count();
    timings.sort();
    let pct = |n: usize, d: usize| 100.0 * n as f64 / d as f64;
    println!(
        "dictionnaire : {} mots, chargé en {:?} (hôte, debug)",
        engine.len(),
        load
    );
    println!(
        "corrigé juste {:.1} % ; faux {:.1} % ; attendu dans le top 3 {:.1} %",
        pct(ok, typos.len()),
        pct(wrong, typos.len()),
        pct(top3, typos.len())
    );
    for (kind, (good, total)) in &by_kind {
        println!(
            "  {kind} : {:.1} % ({good}/{total})",
            100.0 * f64::from(*good) / f64::from(*total)
        );
    }
    println!(
        "sur-correction hors dictionnaire : {:.1} % ({over}/{})",
        pct(over, oov.len()),
        oov.len()
    );
    println!(
        "coût d'une correction : p50 {:?}, p95 {:?}",
        timings[timings.len() / 2],
        timings[timings.len() * 95 / 100]
    );
    assert!(ok >= 160, "pas mieux que la phase 0 : {ok}/200");
    assert!(wrong <= 8, "trop de corrections fausses : {wrong}");
    assert!(
        over * 1000 <= oov.len() * 45,
        "sur-correction au-delà de la phase 0 : {over}"
    );
}
