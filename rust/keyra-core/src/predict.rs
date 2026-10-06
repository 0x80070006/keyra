//! Moteur de correction et de prédiction (phase 5), remplaçant `FrenchCorrector` (Kotlin).
//!
//! - Dictionnaire : trie sur la forme « repliée » (minuscules, sans accents, `’` → `'`), construit en mémoire au
//!   chargement depuis le texte `mot fréquence` (borné et validé ligne à ligne).
//! - Distance d'édition pondérée par le clavier AZERTY de Keyra : touche voisine 0,5 ; lettre oubliée 0,6 ;
//!   apostrophe ou trait d'union oubliés 0,2 ; lettre doublée 0,5 ; inversion 0,7 ; accent différent 0,1.
//! - Complétions : mots dont le début correspond à la frappe.
//! - Probabilité de la lettre suivante (zones de toucher dynamiques).

use std::cmp::Ordering;

/// Taille maximale du fichier dictionnaire accepté (4 Mio).
pub const MAX_DICTIONARY_BYTES: usize = 4 * 1024 * 1024;
/// Longueur maximale d'un mot (caractères repliés).
pub const MAX_WORD: usize = 30;
const MAX_WORDS: usize = 200_000;

/// Un candidat proposé pour un mot tapé.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    /// Forme affichée (accents compris).
    pub word: String,
    /// Coût d'édition pondéré entre la frappe et ce mot.
    pub cost: f32,
    /// Score de classement : plus petit = meilleur.
    pub score: f32,
    /// Fréquence dans le corpus.
    pub frequency: u32,
    /// Vrai si le mot prolonge la frappe (complétion).
    pub completion: bool,
}

struct Word {
    display: String,
    frequency: u32,
    log_frequency: f32,
}

/// Résultat intermédiaire de la recherche : léger, sans copie de chaîne.
#[derive(Clone, Copy)]
struct Hit {
    score: f32,
    cost: f32,
    word: u32,
    completion: bool,
}

#[derive(Default)]
struct Node {
    children: Vec<(u8, u32)>,
    words: Vec<u32>,
    subtree: u64,
}

/// Dictionnaire et moteur de recherche.
pub struct Engine {
    words: Vec<Word>,
    nodes: Vec<Node>,
    neighbors: [[bool; 26]; 26],
}

/// Replie un caractère : minuscule sans accent. `None` si le caractère ne peut pas appartenir à un mot.
fn fold_char(c: char, out: &mut Vec<u8>) -> bool {
    let lower = c.to_lowercase().next().unwrap_or(c);
    let mapped: &[u8] = match lower {
        'a'..='z' => {
            out.push(u8::try_from(lower).unwrap_or(b'?'));
            return true;
        }
        'à' | 'â' | 'ä' | 'á' | 'ã' | 'å' => b"a",
        'æ' => b"ae",
        'ç' => b"c",
        'é' | 'è' | 'ê' | 'ë' => b"e",
        'î' | 'ï' | 'í' | 'ì' => b"i",
        'ô' | 'ö' | 'ó' | 'ò' | 'õ' => b"o",
        'œ' => b"oe",
        'ù' | 'û' | 'ü' | 'ú' => b"u",
        'ÿ' | 'ý' => b"y",
        'ñ' => b"n",
        '\'' | '’' | '‘' => b"'",
        '-' => b"-",
        _ => return false,
    };
    out.extend_from_slice(mapped);
    true
}

/// Forme repliée d'un mot, ou `None` si le mot contient un caractère hors alphabet.
#[must_use]
pub fn fold(word: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(word.len());
    for c in word.chars() {
        if !fold_char(c, &mut out) {
            return None;
        }
    }
    (!out.is_empty() && out.len() <= MAX_WORD).then_some(out)
}

fn letter_index(b: u8) -> Option<usize> {
    b.is_ascii_lowercase().then(|| usize::from(b - b'a'))
}

/// Voisinage des touches dans la géométrie de Keyra (repère 684 × 612) : centres à moins de 1,5 touche.
fn azerty_neighbors() -> [[bool; 26]; 26] {
    const XS: [f32; 10] = [8.0, 76.0, 143.0, 211.0, 279.0, 347.0, 414.0, 482.0, 550.0, 618.0];
    const ROW3: [f32; 6] = [110.0, 177.0, 245.0, 312.0, 380.0, 448.0];
    let rows: [&[u8]; 3] = [b"azertyuiop", b"qsdfghjklm", b"wxcvbn"];
    let mut centers = [(0f32, 0f32); 26];
    for (r, letters) in rows.iter().enumerate() {
        for (i, &c) in letters.iter().enumerate() {
            let x = if r == 2 {
                ROW3.get(i).copied().unwrap_or(0.0)
            } else {
                XS.get(i).copied().unwrap_or(0.0)
            } + 29.0;
            let row = f32::from(u8::try_from(r).unwrap_or(0));
            if let Some(center) = letter_index(c).and_then(|index| centers.get_mut(index)) {
                *center = (x / 68.0, (95.0 + 105.5 * row + 42.0) / 105.5);
            }
        }
    }
    let mut table = [[false; 26]; 26];
    for (a, row) in table.iter_mut().enumerate() {
        for (b, cell) in row.iter_mut().enumerate() {
            let (pa, pb) = (
                centers.get(a).copied().unwrap_or_default(),
                centers.get(b).copied().unwrap_or_default(),
            );
            let distance = ((pa.0 - pb.0).powi(2) + (pa.1 - pb.1).powi(2)).sqrt();
            *cell = a != b && distance <= 1.5;
        }
    }
    table
}

const NEIGHBOR: f32 = 0.5;
const SUBSTITUTION: f32 = 1.0;
const OMISSION: f32 = 0.6;
const OMITTED_MARK: f32 = 0.2;
const EXTRA: f32 = 0.8;
const DOUBLED: f32 = 0.5;
const TRANSPOSITION: f32 = 0.7;
const ACCENT: f32 = 0.1;
const COMPLETION_PER_CHAR: f32 = 0.12;
const MAX_COMPLETION_EXTRA: usize = 6;

impl Engine {
    /// Construit le moteur depuis le texte `mot fréquence` (une entrée par ligne). Les lignes invalides sont ignorées.
    #[must_use]
    pub fn from_text(text: &str) -> Self {
        let mut engine = Self {
            words: Vec::new(),
            nodes: vec![Node::default()],
            neighbors: azerty_neighbors(),
        };
        if text.len() > MAX_DICTIONARY_BYTES {
            return engine;
        }
        for line in text.lines().take(MAX_WORDS) {
            let mut parts = line.split_whitespace();
            let (Some(word), Some(count)) = (parts.next(), parts.next()) else {
                continue;
            };
            let Ok(frequency) = count.parse::<u32>() else { continue };
            if word.chars().count() < 2 {
                continue;
            }
            let display = word.to_lowercase().replace('’', "'");
            let Some(folded) = fold(&display) else { continue };
            engine.insert(display, &folded, frequency);
        }
        engine.compute_subtrees(0);
        engine
    }

    fn insert(&mut self, display: String, folded: &[u8], frequency: u32) {
        let id = u32::try_from(self.words.len()).unwrap_or(u32::MAX);
        #[allow(clippy::cast_precision_loss)]
        let log_frequency = (frequency.max(1) as f32).ln();
        self.words.push(Word {
            display,
            frequency,
            log_frequency,
        });
        let mut node = 0usize;
        for &b in folded {
            let existing = self
                .nodes
                .get(node)
                .and_then(|n| n.children.iter().find(|(c, _)| *c == b).map(|(_, i)| *i));
            node = if let Some(child) = existing {
                child as usize
            } else {
                let child = self.nodes.len();
                self.nodes.push(Node::default());
                if let Some(parent) = self.nodes.get_mut(node) {
                    parent.children.push((b, u32::try_from(child).unwrap_or(u32::MAX)));
                }
                child
            };
        }
        if let Some(n) = self.nodes.get_mut(node) {
            n.words.push(id);
        }
    }

    fn compute_subtrees(&mut self, root: usize) {
        // Parcours postfixe itératif : pas de récursion profonde.
        let mut order = Vec::with_capacity(self.nodes.len());
        let mut stack = vec![root];
        while let Some(n) = stack.pop() {
            order.push(n);
            if let Some(node) = self.nodes.get(n) {
                stack.extend(node.children.iter().map(|(_, c)| *c as usize));
            }
        }
        for &n in order.iter().rev() {
            let own: u64 = self.nodes.get(n).map_or(0, |node| {
                node.words
                    .iter()
                    .map(|&w| u64::from(self.words.get(w as usize).map_or(0, |x| x.frequency)))
                    .sum()
            });
            let children: u64 = self.nodes.get(n).map_or(0, |node| {
                node.children
                    .iter()
                    .map(|(_, c)| self.nodes.get(*c as usize).map_or(0, |x| x.subtree))
                    .sum()
            });
            if let Some(node) = self.nodes.get_mut(n) {
                node.subtree = own + children;
            }
        }
    }

    /// Nombre de mots chargés.
    #[must_use]
    pub fn len(&self) -> usize {
        self.words.len()
    }

    /// Vrai si aucun mot n'est chargé.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    fn node_for(&self, folded: &[u8]) -> Option<&Node> {
        let mut node = self.nodes.first()?;
        for &b in folded {
            let child = node.children.iter().find(|(c, _)| *c == b)?.1;
            node = self.nodes.get(child as usize)?;
        }
        Some(node)
    }

    /// Fréquence du mot exact (accents compris), ou `None`.
    #[must_use]
    pub fn frequency(&self, word: &str) -> Option<u32> {
        let display = word.to_lowercase().replace('’', "'");
        let folded = fold(&display)?;
        let node = self.node_for(&folded)?;
        node.words
            .iter()
            .filter_map(|&w| self.words.get(w as usize))
            .find(|w| w.display == display)
            .map(|w| w.frequency)
    }

    /// Vrai si le mot exact figure dans le dictionnaire.
    #[must_use]
    pub fn contains(&self, word: &str) -> bool {
        self.frequency(word).is_some()
    }

    /// Probabilité (0 à 255) de chaque lettre a–z après le préfixe tapé (zones de toucher dynamiques).
    #[must_use]
    pub fn next_letters(&self, prefix: &str) -> [u8; 26] {
        let mut out = [0u8; 26];
        let Some(folded) = fold(prefix).or_else(|| prefix.is_empty().then(Vec::new)) else {
            return out;
        };
        let Some(node) = self.node_for(&folded) else { return out };
        let total: u64 = node
            .children
            .iter()
            .map(|(_, c)| self.nodes.get(*c as usize).map_or(0, |n| n.subtree))
            .sum();
        if total == 0 {
            return out;
        }
        for (b, child) in &node.children {
            if let (Some(i), Some(n)) = (letter_index(*b), self.nodes.get(*child as usize)) {
                let p = n.subtree.saturating_mul(255) / total;
                if let Some(slot) = out.get_mut(i) {
                    *slot = u8::try_from(p).unwrap_or(255);
                }
            }
        }
        out
    }

    fn substitution(&self, a: u8, b: u8) -> f32 {
        if a == b {
            return 0.0;
        }
        match (letter_index(a), letter_index(b)) {
            (Some(x), Some(y)) if self.neighbors.get(x).and_then(|r| r.get(y)).copied().unwrap_or(false) => NEIGHBOR,
            _ => SUBSTITUTION,
        }
    }

    fn extra_cost(&self, typed: &[u8], j: usize) -> f32 {
        // Caractère tapé en trop : moins cher s'il double le précédent ou touche son voisin.
        let current = typed.get(j).copied();
        let previous = j.checked_sub(1).and_then(|p| typed.get(p)).copied();
        match (current, previous) {
            (Some(c), Some(p)) if c == p || self.substitution(c, p) <= NEIGHBOR => DOUBLED,
            _ => EXTRA,
        }
    }

    /// Candidats pour `typed`, triés du meilleur au moins bon. `tolerance` de 0 à 100.
    #[must_use]
    pub fn candidates(&self, typed: &str, tolerance: u32, limit: usize) -> Vec<Candidate> {
        let display_typed = typed.to_lowercase().replace('’', "'");
        let Some(q) = fold(&display_typed) else {
            return Vec::new();
        };
        let max_cost = max_cost(q.len(), tolerance);
        let width = q.len() + 1;
        // Lignes de la matrice de distance, une par profondeur : allouées une fois par recherche.
        let mut rows: Vec<Vec<f32>> = vec![vec![0.0; width]; MAX_WORD + 2];
        if let Some(first) = rows.first_mut() {
            for (j, cell) in first.iter_mut().enumerate() {
                *cell = if j == 0 { 0.0 } else { previous_extra(self, &q, j) };
            }
        }
        let mut path: Vec<u8> = Vec::with_capacity(MAX_WORD + 1);
        let mut found: Vec<Hit> = Vec::new();
        // (nœud, profondeur, index d'enfant suivant, meilleur alignement de toute la frappe sur un préfixe du chemin)
        let mut stack: Vec<(usize, usize, usize, f32)> = Vec::new();
        stack.push((0, 0, 0, if q.is_empty() { 0.0 } else { f32::MAX }));
        while let Some(&mut (node_id, depth, ref mut next, best_prefix)) = stack.last_mut() {
            let Some(node) = self.nodes.get(node_id) else {
                stack.pop();
                continue;
            };
            if *next == 0 && depth > 0 {
                self.collect(
                    node,
                    rows.get(depth).map_or(&[][..], Vec::as_slice),
                    best_prefix,
                    &display_typed,
                    q.len(),
                    depth,
                    max_cost,
                    &mut found,
                );
            }
            let Some(&(byte, child)) = node.children.get(*next) else {
                stack.pop();
                path.pop();
                continue;
            };
            *next += 1;
            if depth + 1 > MAX_WORD {
                continue;
            }
            let (head, tail) = rows.split_at_mut(depth + 1);
            let (Some(prev), Some(row)) = (head.last(), tail.first_mut()) else {
                continue;
            };
            let older = depth.checked_sub(1).and_then(|d| head.get(d));
            let p_char = byte;
            let p_prev = path.last().copied();
            let omit = if byte == b'\'' || byte == b'-' {
                OMITTED_MARK
            } else {
                OMISSION
            };
            if let (Some(r0), Some(p0)) = (row.first_mut(), prev.first()) {
                *r0 = p0 + omit;
            }
            let mut minimum = row.first().copied().unwrap_or(f32::MAX);
            for j in 1..width {
                let up = prev.get(j).copied().unwrap_or(f32::MAX) + omit;
                let left = row.get(j - 1).copied().unwrap_or(f32::MAX) + self.extra_cost(&q, j - 1);
                let diagonal = prev.get(j - 1).copied().unwrap_or(f32::MAX)
                    + q.get(j - 1).map_or(SUBSTITUTION, |&c| self.substitution(p_char, c));
                let mut best = up.min(left).min(diagonal);
                if let (Some(older), Some(pp), true) = (older, p_prev, j >= 2) {
                    if q.get(j - 1) == Some(&pp) && q.get(j - 2) == Some(&p_char) {
                        best = best.min(older.get(j - 2).copied().unwrap_or(f32::MAX) + TRANSPOSITION);
                    }
                }
                if let Some(cell) = row.get_mut(j) {
                    *cell = best;
                }
                minimum = minimum.min(best);
            }
            // On descend tant qu'une correction reste possible, ou tant qu'une complétion de la frappe l'est.
            let complete = row.get(q.len()).copied().unwrap_or(f32::MAX);
            let child_best = best_prefix.min(complete);
            let prefix_ok = depth < q.len() + MAX_COMPLETION_EXTRA && child_best <= 0.6;
            if minimum <= max_cost || prefix_ok {
                path.push(byte);
                stack.push((child as usize, depth + 1, 0, child_best));
            }
        }
        // Chaque mot n'appartient qu'à un nœud, visité une fois : pas de doublon possible.
        found.sort_unstable_by(|a, b| a.score.partial_cmp(&b.score).unwrap_or(Ordering::Equal));
        found
            .iter()
            .take(limit)
            .filter_map(|h| {
                let word = self.words.get(h.word as usize)?;
                Some(Candidate {
                    word: word.display.clone(),
                    cost: h.cost,
                    score: h.score,
                    frequency: word.frequency,
                    completion: h.completion,
                })
            })
            .collect()
    }

    #[allow(clippy::too_many_arguments)]
    fn collect(
        &self,
        node: &Node,
        row: &[f32],
        best_prefix: f32,
        typed: &str,
        q_len: usize,
        depth: usize,
        max_cost: f32,
        found: &mut Vec<Hit>,
    ) {
        if node.words.is_empty() {
            return;
        }
        let edit = row.get(q_len).copied().unwrap_or(f32::MAX);
        // Complétion : la frappe entière s'aligne sur un préfixe du mot (meilleur alignement le long du chemin),
        // plus un petit coût par caractère ajouté.
        let completion_cost = if depth > q_len && best_prefix < f32::MAX {
            let extra = f32::from(u8::try_from(depth - q_len).unwrap_or(u8::MAX));
            best_prefix + COMPLETION_PER_CHAR * extra
        } else {
            f32::MAX
        };
        let (cost, completion) = if completion_cost < edit {
            (completion_cost, true)
        } else {
            (edit, false)
        };
        if cost > max_cost.max(1.0) {
            return;
        }
        for &w in &node.words {
            let Some(word) = self.words.get(w as usize) else {
                continue;
            };
            let accent = if word.display != typed && depth == q_len && edit == 0.0 {
                ACCENT
            } else {
                0.0
            };
            let total = cost + accent;
            let score = total * 2.2 - word.log_frequency * 0.25 + if completion { 0.6 } else { 0.0 };
            found.push(Hit {
                score,
                cost: total,
                word: w,
                completion,
            });
        }
    }

    /// Suggestions (les `limit` meilleures) et correction automatique, en une seule recherche.
    #[must_use]
    pub fn analyze(&self, typed: &str, tolerance: u32, limit: usize) -> (Vec<Candidate>, Option<String>) {
        let mut candidates = self.candidates(typed, tolerance, limit.max(8));
        let correction = self.decide(typed, tolerance, &candidates);
        candidates.truncate(limit);
        (candidates, correction)
    }

    /// Correction automatique de `typed`, ou `None` s'il faut garder la frappe telle quelle.
    #[must_use]
    pub fn correction(&self, typed: &str, tolerance: u32) -> Option<String> {
        self.analyze(typed, tolerance, 0).1
    }

    fn decide(&self, typed: &str, tolerance: u32, candidates: &[Candidate]) -> Option<String> {
        if tolerance == 0 || typed.chars().count() < 3 || typed.chars().next().is_some_and(char::is_uppercase) {
            return None;
        }
        let typed_frequency = self.frequency(typed);
        let length = typed.chars().count();
        // Complétion retenue seulement vers un mot très courant : une lettre finale oubliée (« avoi » → « avoir »)
        // ou un mot long abrégé (« maintn » → « maintenant »).
        let edits: Vec<&Candidate> = candidates
            .iter()
            .filter(|c| {
                let extra = c.word.chars().count().saturating_sub(length);
                c.cost > 0.0
                    && (!c.completion || (c.frequency >= 20_000 && (extra == 1 || (length >= 6 && c.cost <= 1.2))))
            })
            .collect();
        let best = *edits.first()?;
        // Mot connu : on ne le remplace que par sa forme accentuée nettement plus fréquente (« deja » → « déjà »).
        if let Some(known) = typed_frequency {
            let accent_only = best.cost <= ACCENT + f32::EPSILON;
            return (accent_only && best.frequency / known.max(1) >= 20).then(|| best.word.clone());
        }
        let cost_limit = if best.completion {
            1.2
        } else {
            correction_cost(length, tolerance)
        };
        if best.cost > cost_limit || best.frequency < min_frequency(tolerance, length) {
            return None;
        }
        // Marge de confiance : le meilleur doit nettement dépasser le deuxième, sauf distance plus petite.
        if let Some(second) = edits.get(1) {
            let margin = 0.9 - 0.6 * f32::from(u16::try_from(tolerance.min(100)).unwrap_or(100)) / 100.0;
            if second.cost <= best.cost && second.score - best.score < margin {
                return None;
            }
        }
        Some(best.word.clone())
    }
}

fn previous_extra(engine: &Engine, q: &[u8], j: usize) -> f32 {
    (0..j).map(|k| engine.extra_cost(q, k)).sum()
}

fn max_cost(length: usize, tolerance: u32) -> f32 {
    let base = match length {
        0..=3 => 0.6,
        4..=5 => 1.1,
        _ => 1.5,
    };
    let factor = 0.5 + f32::from(u16::try_from(tolerance.min(100)).unwrap_or(100)) / 100.0;
    base * factor
}

/// Coût maximal d'une correction automatique (plus strict que pour les suggestions) : une vraie faute coûte
/// presque toujours 0,7 ou moins ; au-delà, c'est souvent un mot valide absent du dictionnaire (« kotlin »).
fn correction_cost(length: usize, tolerance: u32) -> f32 {
    let t = f32::from(u16::try_from(tolerance.min(100)).unwrap_or(100)) / 100.0;
    0.5 + 0.6 * t + if length >= 7 { 0.3 } else { 0.0 }
}

fn min_frequency(tolerance: u32, length: usize) -> u32 {
    let base = match tolerance {
        0..=34 => 10_000,
        35..=69 => 2_500,
        _ => 500,
    };
    if length <= 3 { base.max(50_000) } else { base }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    fn engine() -> Engine {
        Engine::from_text(
            "venir 90000\navenir 200000\nmaintenant 120000\nbonjour 110000\nbonsoir 70000\nj'arrive 80000\nmaison 65000\n\
             voir 400000\ndéjà 300000\ndeja 2000\ntrès 500000\nmaison 65000\nmaisons 9000\nmais 900000\n",
        )
    }

    #[test]
    fn folds_accents_and_apostrophes() {
        assert_eq!(fold("Déjà"), Some(b"deja".to_vec()));
        assert_eq!(fold("Cœur"), Some(b"coeur".to_vec()));
        assert_eq!(fold("aujourd’hui"), Some(b"aujourd'hui".to_vec()));
        assert_eq!(fold("mot2"), None);
    }

    #[test]
    fn corrects_typical_typos() {
        let e = engine();
        assert_eq!(e.correction("vnir", 55).as_deref(), Some("venir"));
        assert_eq!(e.correction("bonjuor", 55).as_deref(), Some("bonjour"));
        assert_eq!(e.correction("jarrive", 55).as_deref(), Some("j'arrive"));
        assert_eq!(e.correction("maintn", 70).as_deref(), Some("maintenant"));
        assert_eq!(e.correction("tres", 55).as_deref(), Some("très"));
        assert_eq!(e.correction("deja", 55).as_deref(), Some("déjà"));
    }

    #[test]
    fn keeps_known_words_and_names() {
        let e = engine();
        assert_eq!(e.correction("maison", 70), None);
        assert_eq!(e.correction("Paris", 100), None);
        assert_eq!(e.correction("bonjour", 0), None);
        assert_eq!(e.correction("xyzzyq", 55), None);
    }

    #[test]
    fn completes_prefixes() {
        let e = engine();
        assert!(
            e.candidates("bonj", 55, 5)
                .iter()
                .any(|c| c.word == "bonjour" && c.completion)
        );
    }

    #[test]
    fn next_letter_probabilities() {
        let e = engine();
        let p = e.next_letters("ma");
        let i = usize::from(b'i' - b'a');
        assert!(
            p.get(i).copied().unwrap_or(0) > 200,
            "après « ma », presque toujours « i »"
        );
    }

    proptest! {
        #[test]
        fn never_panics(text in ".{0,40}") {
            let e = engine();
            let _ = e.candidates(&text, 55, 3);
            let _ = e.correction(&text, 100);
            let _ = e.next_letters(&text);
        }

        #[test]
        fn loader_never_panics(text in ".{0,400}") {
            let _ = Engine::from_text(&text);
        }
    }
}
