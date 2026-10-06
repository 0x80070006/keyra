//! Détecteur local de secrets et de données personnelles.
//!
//! Sert à décider si un texte peut être **conservé** : appris par le dictionnaire,
//! gardé dans l'historique du presse-papiers. En cas de doute, on préfère classer
//! un texte comme secret : un faux positif coûte une suggestion ou une entrée
//! d'historique en moins, alors qu'un faux négatif conserve un secret sur l'appareil.

/// Taille maximale analysée, en octets UTF-8. Au-delà, l'appelant doit traiter le texte comme secret.
pub const MAX_INPUT_BYTES: usize = 16 * 1024;

/// Nature du contenu sensible trouvé dans un texte. Les valeurs numériques font partie
/// du contrat JNI (`KeyraCore.classify`) : ne jamais les renuméroter.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Sensitivity {
    /// Rien de sensible détecté.
    None = 0,
    /// Numéro de carte de paiement (13 à 19 chiffres, clé de Luhn valide).
    CardNumber = 1,
    /// IBAN valide (contrôle modulo 97).
    Iban = 2,
    /// Code à usage unique ou code PIN : texte fait uniquement de 4 à 8 chiffres.
    OneTimeCode = 3,
    /// Clé d'API, jeton ou clé privée au format reconnaissable.
    ApiKey = 4,
    /// Jeton à forte entropie (mot de passe probable).
    HighEntropy = 5,
    /// Adresse e-mail : donnée personnelle, pas un secret.
    Email = 6,
    /// Numéro de téléphone : donnée personnelle, pas un secret.
    Phone = 7,
}

impl Sensitivity {
    /// Vrai pour les secrets : un tel texte ne doit jamais être écrit sur disque.
    #[must_use]
    pub fn is_secret(self) -> bool {
        matches!(
            self,
            Self::CardNumber | Self::Iban | Self::OneTimeCode | Self::ApiKey | Self::HighEntropy
        )
    }

    /// Code stable transmis à Kotlin.
    #[must_use]
    pub fn code(self) -> i32 {
        self as i32
    }
}

/// Classe un texte. Les secrets sont prioritaires sur les données personnelles.
#[must_use]
pub fn classify(text: &str) -> Sensitivity {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Sensitivity::None;
    }
    if trimmed.contains("-----BEGIN") {
        return Sensitivity::ApiKey;
    }
    if is_one_time_code(trimmed) {
        return Sensitivity::OneTimeCode;
    }
    if contains_card_number(trimmed) {
        return Sensitivity::CardNumber;
    }
    if contains_iban(trimmed) {
        return Sensitivity::Iban;
    }
    let mut personal = Sensitivity::None;
    for token in trimmed.split_whitespace().map(strip_punctuation) {
        if is_api_key(token) {
            return Sensitivity::ApiKey;
        }
        if is_email(token) {
            personal = Sensitivity::Email;
            continue;
        }
        if token_is_high_entropy(token) {
            return Sensitivity::HighEntropy;
        }
    }
    if personal == Sensitivity::None && is_phone(trimmed) {
        personal = Sensitivity::Phone;
    }
    personal
}

fn strip_punctuation(token: &str) -> &str {
    token.trim_matches(|c: char| {
        matches!(
            c,
            '"' | '\'' | '(' | ')' | '[' | ']' | '<' | '>' | ',' | ';' | '«' | '»'
        )
    })
}

/// Texte composé uniquement de 4 à 8 chiffres, éventuellement coupé par des espaces (« 123 456 »).
fn is_one_time_code(text: &str) -> bool {
    let digits = text.chars().filter(char::is_ascii_digit).count();
    (4..=8).contains(&digits) && text.chars().all(|c| c.is_ascii_digit() || c == ' ')
}

/// Cherche une suite de 13 à 19 chiffres (séparateurs espace ou tiret simples autorisés) valide selon Luhn.
fn contains_card_number(text: &str) -> bool {
    let mut digits: Vec<u8> = Vec::with_capacity(19);
    let mut previous_was_separator = false;
    for c in text.chars() {
        if let Some(d) = c.to_digit(10) {
            digits.push(u8::try_from(d).unwrap_or(0));
            previous_was_separator = false;
        } else if (c == ' ' || c == '-') && !digits.is_empty() && !previous_was_separator {
            previous_was_separator = true;
        } else {
            if is_card(&digits) {
                return true;
            }
            digits.clear();
            previous_was_separator = false;
        }
    }
    is_card(&digits)
}

fn is_card(digits: &[u8]) -> bool {
    (13..=19).contains(&digits.len()) && luhn_valid(digits)
}

/// Contrôle de Luhn : utilisé par toutes les grandes cartes de paiement.
#[must_use]
pub fn luhn_valid(digits: &[u8]) -> bool {
    let mut sum = 0u32;
    for (i, &d) in digits.iter().rev().enumerate() {
        let mut v = u32::from(d);
        if i % 2 == 1 {
            v *= 2;
            if v > 9 {
                v -= 9;
            }
        }
        sum += v;
    }
    !digits.is_empty() && sum % 10 == 0
}

/// Cherche un IBAN : deux lettres, deux chiffres de contrôle, puis 11 à 30 caractères alphanumériques,
/// éventuellement groupés par quatre avec des espaces.
fn contains_iban(text: &str) -> bool {
    let chars: Vec<char> = text.chars().collect();
    for start in 0..chars.len() {
        let boundary = start == 0 || chars.get(start - 1).is_some_and(|c| !c.is_ascii_alphanumeric());
        if !boundary {
            continue;
        }
        let head: Vec<char> = chars.iter().skip(start).take(4).copied().collect();
        let looks_like_iban = head.len() == 4
            && head.iter().take(2).all(char::is_ascii_alphabetic)
            && head.iter().skip(2).all(char::is_ascii_digit);
        if !looks_like_iban {
            continue;
        }
        let mut compact = String::with_capacity(34);
        let mut previous_space = false;
        for &c in chars.iter().skip(start) {
            if c.is_ascii_alphanumeric() {
                compact.push(c.to_ascii_uppercase());
                previous_space = false;
                if compact.len() == 34 {
                    break;
                }
            } else if c == ' ' && !previous_space {
                previous_space = true;
            } else {
                break;
            }
        }
        if (15..=34).any(|n| compact.len() >= n && iban_valid(compact.get(..n).unwrap_or(""))) {
            return true;
        }
    }
    false
}

/// Contrôle modulo 97 d'un IBAN compact en majuscules (norme ISO 13616).
#[must_use]
pub fn iban_valid(iban: &str) -> bool {
    if iban.len() < 15 || !iban.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit()) {
        return false;
    }
    let (head, tail) = iban.split_at(4);
    let mut remainder = 0u32;
    for c in tail.chars().chain(head.chars()) {
        let value = c.to_digit(36).unwrap_or(0);
        remainder = if value >= 10 {
            (remainder * 100 + value) % 97
        } else {
            (remainder * 10 + value) % 97
        };
    }
    remainder == 1
}

fn is_api_key(token: &str) -> bool {
    let tail_len = |prefix: &str| token.len().saturating_sub(prefix.len());
    let alnum_tail = |prefix: &str| {
        token
            .get(prefix.len()..)
            .is_some_and(|t| t.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-'))
    };
    for prefix in [
        "ghp_",
        "gho_",
        "ghu_",
        "ghs_",
        "ghr_",
        "github_pat_",
        "glpat-",
        "sk-",
        "xoxb-",
        "xoxa-",
        "xoxp-",
        "xoxr-",
        "xoxs-",
    ] {
        if token.starts_with(prefix) && tail_len(prefix) >= 20 && alnum_tail(prefix) {
            return true;
        }
    }
    if token.starts_with("AKIA")
        && token.len() == 20
        && token.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
    {
        return true;
    }
    if token.starts_with("AIza") && token.len() == 39 && alnum_tail("AIza") {
        return true;
    }
    is_jwt(token)
}

/// Jeton JWT : trois segments base64url séparés par des points, le premier commence par `eyJ`.
fn is_jwt(token: &str) -> bool {
    let parts: Vec<&str> = token.split('.').collect();
    parts.len() == 3
        && token.starts_with("eyJ")
        && token.len() >= 30
        && parts
            .iter()
            .all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'))
}

fn is_email(token: &str) -> bool {
    let mut parts = token.split('@');
    match (parts.next(), parts.next(), parts.next()) {
        (Some(local), Some(domain), None) => {
            !local.is_empty() && domain.contains('.') && !domain.starts_with('.') && !domain.ends_with('.')
        }
        _ => false,
    }
}

fn is_phone(text: &str) -> bool {
    let digits = text.chars().filter(char::is_ascii_digit).count();
    (9..=15).contains(&digits)
        && (text.starts_with('+') || text.starts_with('0'))
        && text
            .chars()
            .all(|c| c.is_ascii_digit() || matches!(c, ' ' | '+' | '.' | '-' | '(' | ')'))
}

/// Un jeton, ou chaque segment d'une URL, est-il un secret probable ?
fn token_is_high_entropy(token: &str) -> bool {
    if token.starts_with("http://") || token.starts_with("https://") || token.starts_with("www.") {
        return token.split(['/', '?', '&', '=', '#']).any(is_high_entropy);
    }
    is_high_entropy(token)
}

/// Règles volontairement simples et documentées :
/// - A : 12 caractères ou plus, au moins 3 classes, entropie ≥ 3,5 bits par caractère ;
/// - B : 24 caractères ou plus, au moins 2 classes, entropie ≥ 4,0 bits par caractère (base64) ;
/// - C : 32 chiffres hexadécimaux ou plus (empreintes, clés en hexadécimal).
#[must_use]
pub fn is_high_entropy(token: &str) -> bool {
    let length = token.chars().count();
    if !(12..=512).contains(&length) {
        return false;
    }
    if length >= 32 && token.chars().all(|c| c.is_ascii_hexdigit()) && token.chars().any(|c| c.is_ascii_digit()) {
        return true;
    }
    // Identifiants faits de mots (« Clavier_AZERTY », « mot-de-passe ») : pas un secret probable.
    // Limite connue : une phrase de passe en mots du dictionnaire (« Cheval-Pile_Agrafe ») n'est pas détectée.
    if token
        .split(['_', '-', '.'])
        .all(|part| !part.is_empty() && part.chars().all(char::is_alphabetic))
    {
        return false;
    }
    let classes = [
        token.chars().any(char::is_lowercase),
        token.chars().any(char::is_uppercase),
        token.chars().any(|c| c.is_ascii_digit()),
        token.chars().any(|c| !c.is_alphanumeric()),
    ]
    .iter()
    .filter(|&&present| present)
    .count();
    let entropy = shannon_entropy(token);
    (classes >= 3 && entropy >= 3.5) || (length >= 24 && classes >= 2 && entropy >= 4.0)
}

/// Entropie de Shannon, en bits par caractère.
#[must_use]
pub fn shannon_entropy(text: &str) -> f64 {
    let mut counts: Vec<(char, u32)> = Vec::new();
    let mut total = 0u32;
    for c in text.chars() {
        total += 1;
        match counts.iter_mut().find(|(k, _)| *k == c) {
            Some((_, n)) => *n += 1,
            None => counts.push((c, 1)),
        }
    }
    if total == 0 {
        return 0.0;
    }
    let total = f64::from(total);
    counts
        .iter()
        .map(|&(_, n)| f64::from(n) / total)
        .map(|p| -p * p.log2())
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn detects_card_numbers() {
        assert_eq!(classify("4111 1111 1111 1111"), Sensitivity::CardNumber);
        assert_eq!(classify("ma carte 5555-5555-5555-4444 merci"), Sensitivity::CardNumber);
        assert_eq!(classify("378282246310005"), Sensitivity::CardNumber);
    }

    #[test]
    fn rejects_invalid_luhn() {
        assert_ne!(classify("4111 1111 1111 1112"), Sensitivity::CardNumber);
    }

    #[test]
    fn detects_iban() {
        assert_eq!(classify("FR76 3000 6000 0112 3456 7890 189"), Sensitivity::Iban);
        assert_eq!(
            classify("virement sur GB82WEST12345698765432 demain"),
            Sensitivity::Iban
        );
        assert_ne!(classify("FR76 3000 6000 0112 3456 7890 188"), Sensitivity::Iban);
    }

    #[test]
    fn detects_one_time_codes() {
        assert_eq!(classify("482913"), Sensitivity::OneTimeCode);
        assert_eq!(classify(" 482 913 "), Sensitivity::OneTimeCode);
        assert_eq!(classify("1234"), Sensitivity::OneTimeCode);
        assert_eq!(classify("rendez-vous à 1430"), Sensitivity::None);
    }

    #[test]
    fn detects_api_keys_and_tokens() {
        assert_eq!(
            classify("ghp_abcdefghijklmnopqrstuvwxyz0123456789"),
            Sensitivity::ApiKey
        );
        assert_eq!(classify("AKIAIOSFODNN7EXAMPLE"), Sensitivity::ApiKey);
        assert_eq!(classify("-----BEGIN OPENSSH PRIVATE KEY-----"), Sensitivity::ApiKey);
        assert_eq!(
            classify("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlLXRlc3Q"),
            Sensitivity::ApiKey
        );
    }

    #[test]
    fn detects_high_entropy_passwords() {
        assert_eq!(classify("Tr0ub4dour&3xQ!"), Sensitivity::HighEntropy);
        assert_eq!(
            classify("token=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA"),
            Sensitivity::HighEntropy
        );
        assert_eq!(
            classify("da39a3ee5e6b4b0d3255bfef95601890afd80709"),
            Sensitivity::HighEntropy
        );
    }

    #[test]
    fn keeps_ordinary_french_text() {
        for text in [
            "Bonjour, on se voit demain à la boulangerie ?",
            "anticonstitutionnellement",
            "C'est l'été, il fait 30 degrés.",
            "https://fr.wikipedia.org/wiki/Clavier_AZERTY",
            "rendez-vous le 12/10/2026 à 14h30",
            "Keyra",
            "Clavier_AZERTY",
            "Jean-Baptiste.Poquelin",
        ] {
            assert!(!classify(text).is_secret(), "faux positif : {text}");
        }
    }

    #[test]
    fn personal_data_is_not_secret() {
        assert_eq!(classify("camille@example.org"), Sensitivity::Email);
        assert_eq!(classify("06 12 34 56 78"), Sensitivity::Phone);
        assert_eq!(classify("+33 6 12 34 56 78"), Sensitivity::Phone);
        assert!(!Sensitivity::Email.is_secret());
    }

    #[test]
    fn codes_are_stable() {
        let expected = [
            (Sensitivity::None, 0),
            (Sensitivity::CardNumber, 1),
            (Sensitivity::Iban, 2),
            (Sensitivity::OneTimeCode, 3),
            (Sensitivity::ApiKey, 4),
            (Sensitivity::HighEntropy, 5),
            (Sensitivity::Email, 6),
            (Sensitivity::Phone, 7),
        ];
        for (kind, code) in expected {
            assert_eq!(kind.code(), code);
        }
    }

    fn with_luhn(mut digits: Vec<u8>) -> Vec<u8> {
        for check in 0..10 {
            digits.push(check);
            if luhn_valid(&digits) {
                return digits;
            }
            digits.pop();
        }
        digits
    }

    proptest! {
        #[test]
        fn never_panics(text in any::<String>()) {
            let _ = classify(&text);
        }

        #[test]
        fn valid_card_numbers_are_detected(body in proptest::collection::vec(0u8..10, 12..=18)) {
            let digits = with_luhn(body);
            let text: String = digits.iter().map(|d| char::from(b'0' + d)).collect();
            prop_assert!(classify(&text).is_secret());
        }

        #[test]
        fn lowercase_words_are_not_secrets(word in "[a-zàâçéèêëîïôûùüÿœ]{1,25}") {
            prop_assert!(!classify(&word).is_secret());
        }
    }
}
