//! Pont JNI minimal vers `keyra-core`, pour `com.example.app_clavier.core.KeyraCore`.
//!
//! Règles de ce crate :
//! - c'est le **seul** endroit autorisé à contenir du code `unsafe` ; chaque bloc porte un commentaire `SAFETY:` ;
//! - Kotlin passe du texte en `byte[]` UTF-8 (pas de `String` Java : pas d'UTF-8 modifié, et le tampon peut être effacé) ;
//! - toute erreur renvoie [`ERROR`] (ou `null`) et l'appelant Kotlin échoue fermé ;
//! - aucune panique ne traverse la frontière (`catch_unwind`) ;
//! - toute copie Rust de données sensibles est effacée (`zeroize`) avant de rendre la main ;
//! - la clé de données du coffre ne quitte jamais ce processus Rust : Kotlin ne la revoit plus après `vaultUnlock`.
//!
//! Les tests de ce pont sont des tests instrumentés Android (`KeyraCoreTest`, `VaultTest`) : ils passent par la vraie JVM.

use jni_sys::{JNIEnv, jboolean, jbyte, jbyteArray, jclass, jint, jsize};
use keyra_core::vault::{JournalEnd, MAX_FILE, MAX_RECORD, Vault};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;
use zeroize::Zeroize;

/// Valeur renvoyée en cas d'échec : entrée nulle, trop grande, non UTF-8, exception JVM ou panique.
pub const ERROR: jint = -1;

/// Version du contrat entre la bibliothèque native et `KeyraCore.kt`. À incrémenter à chaque changement de signature.
pub const ABI_VERSION: jint = 2;

/// Coffre unique du processus : `None` tant que l'appareil n'a pas été déverrouillé, ou après `vaultLock`.
static VAULT: Mutex<Option<Vault>> = Mutex::new(None);

fn with_vault<T>(f: impl FnOnce(&Vault) -> Option<T>) -> Option<T> {
    let guard = VAULT.lock().ok()?;
    guard.as_ref().and_then(f)
}

/// `KeyraCore.abiVersion(): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_abiVersion(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    ABI_VERSION
}

/// `KeyraCore.classify(utf8: ByteArray): Int` — code de `keyra_core::secret::Sensitivity`, ou [`ERROR`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_classify(
    env: *mut JNIEnv,
    _class: jclass,
    input: jbyteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| classify_array(env, input))).unwrap_or(ERROR)
}

fn classify_array(env: *mut JNIEnv, input: jbyteArray) -> jint {
    let Some(mut buffer) = copy_byte_array(env, input, keyra_core::secret::MAX_INPUT_BYTES) else {
        return ERROR;
    };
    let code = match std::str::from_utf8(&buffer) {
        Ok(text) => keyra_core::secret::classify(text).code(),
        Err(_) => ERROR,
    };
    buffer.zeroize();
    code
}

/// `KeyraCore.vaultUnlock(key: ByteArray): Int` — 0 si la clé de 32 octets est chargée, sinon [`ERROR`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_vaultUnlock(
    env: *mut JNIEnv,
    _class: jclass,
    key: jbyteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(mut bytes) = copy_byte_array(env, key, 64) else {
            return ERROR;
        };
        let vault = Vault::new(&bytes);
        bytes.zeroize();
        match (vault, VAULT.lock()) {
            (Ok(vault), Ok(mut guard)) => {
                *guard = Some(vault);
                0
            }
            _ => ERROR,
        }
    }))
    .unwrap_or(ERROR)
}

/// `KeyraCore.vaultLock()` — détruit la clé (effacée par `Zeroizing`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_vaultLock(_env: *mut JNIEnv, _class: jclass) {
    let _ = catch_unwind(|| {
        if let Ok(mut guard) = VAULT.lock() {
            *guard = None;
        }
    });
}

/// `KeyraCore.vaultIsUnlocked(): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_vaultIsUnlocked(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jboolean {
    let unlocked = catch_unwind(|| VAULT.lock().is_ok_and(|g| g.is_some())).unwrap_or(false);
    jboolean::from(unlocked)
}

/// `KeyraCore.vaultSealFrame(context, nonce, plaintext): ByteArray?` — trame de journal, ou `null`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_vaultSealFrame(
    env: *mut JNIEnv,
    _class: jclass,
    context: jbyteArray,
    nonce: jbyteArray,
    plaintext: jbyteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let context = copy_byte_array(env, context, 256)?;
        let nonce = copy_byte_array(env, nonce, 64)?;
        let mut plain = copy_byte_array(env, plaintext, MAX_RECORD)?;
        let frame = with_vault(|vault| vault.seal_frame(&context, &nonce, &plain).ok());
        plain.zeroize();
        new_byte_array(env, &frame?)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

/// `KeyraCore.vaultReadJournal(context, data): ByteArray?`
///
/// Encodage du résultat : `fin (u8 : 0 complet, 1 fin tronquée, 2 altéré) ‖ nombre (u32) ‖ (longueur u32 ‖ octets)*`,
/// entiers en petit-boutiste. `null` si le coffre est verrouillé ou en cas d'erreur.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_app_1clavier_core_KeyraCore_vaultReadJournal(
    env: *mut JNIEnv,
    _class: jclass,
    context: jbyteArray,
    data: jbyteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let context = copy_byte_array(env, context, 256)?;
        let data = copy_byte_array(env, data, MAX_FILE)?;
        let mut encoded = with_vault(|vault| {
            let journal = vault.read_journal(&context, &data);
            let end: u8 = match journal.end {
                JournalEnd::Complete => 0,
                JournalEnd::TruncatedTail => 1,
                JournalEnd::Corrupted => 2,
            };
            let total: usize = journal.records.iter().map(|r| 4 + r.len()).sum();
            let mut out = Vec::with_capacity(5 + total);
            out.push(end);
            out.extend_from_slice(&u32::try_from(journal.records.len()).ok()?.to_le_bytes());
            for record in &journal.records {
                out.extend_from_slice(&u32::try_from(record.len()).ok()?.to_le_bytes());
                out.extend_from_slice(record);
            }
            Some(out)
        })?;
        let array = new_byte_array(env, &encoded);
        encoded.zeroize();
        array
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

/// Copie un `byte[]` Java dans un `Vec<u8>` d'au plus `max` octets.
fn copy_byte_array(env: *mut JNIEnv, array: jbyteArray, max: usize) -> Option<Vec<u8>> {
    if env.is_null() || array.is_null() {
        return None;
    }
    // SAFETY: `env` est le pointeur JNIEnv que la JVM passe à cette méthode native, sur le thread courant.
    // Il est non nul (vérifié ci-dessus) et valide pendant tout l'appel ; sa table de fonctions est initialisée par la JVM.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, KeyraCoreTest)
    let functions = unsafe { &**env };
    let get_length = functions.GetArrayLength?;
    let get_region = functions.GetByteArrayRegion?;
    let exception_check = functions.ExceptionCheck?;
    // SAFETY: `array` est une référence locale non nulle vers un byte[] reçue en argument, vivante pendant l'appel.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, KeyraCoreTest)
    let java_length = unsafe { get_length(env, array) };
    let length = usize::try_from(java_length).ok()?;
    if length > max {
        return None;
    }
    let mut buffer = vec![0u8; length];
    let region_length = jsize::try_from(length).ok()?;
    // SAFETY: la zone [0, length) est dans le tableau Java (longueur lue juste au-dessus ; un tableau Java ne change
    // pas de taille) et `buffer` contient exactement `length` octets. `jbyte` (i8) a la même disposition que `u8`.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, KeyraCoreTest)
    unsafe {
        get_region(env, array, 0, region_length, buffer.as_mut_ptr().cast::<jbyte>());
    }
    // SAFETY: même `env` ; ExceptionCheck n'a pas de précondition et peut être appelé avec une exception en attente.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, KeyraCoreTest)
    if unsafe { exception_check(env) } != 0 {
        buffer.zeroize();
        return None;
    }
    Some(buffer)
}

/// Crée un `byte[]` Java contenant `bytes`. `None` si la JVM ne peut pas l'allouer.
fn new_byte_array(env: *mut JNIEnv, bytes: &[u8]) -> Option<jbyteArray> {
    if env.is_null() {
        return None;
    }
    let length = jsize::try_from(bytes.len()).ok()?;
    // SAFETY: `env` est le JNIEnv valide du thread courant (voir `copy_byte_array`), non nul.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, VaultTest)
    let functions = unsafe { &**env };
    let new_array = functions.NewByteArray?;
    let set_region = functions.SetByteArrayRegion?;
    let exception_check = functions.ExceptionCheck?;
    // SAFETY: NewByteArray n'a pas d'autre précondition qu'un env valide ; il renvoie null (et une exception) si l'allocation échoue.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, VaultTest)
    let array = unsafe { new_array(env, length) };
    if array.is_null() {
        return None;
    }
    // SAFETY: `array` vient d'être alloué avec exactement `length` éléments ; `bytes` contient `length` octets lisibles ;
    // `jbyte` (i8) a la même disposition que `u8`.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, VaultTest)
    unsafe {
        set_region(env, array, 0, length, bytes.as_ptr().cast::<jbyte>());
    }
    // SAFETY: même `env` ; ExceptionCheck n'a pas de précondition.
    // nosemgrep: rust.lang.security.unsafe-usage.unsafe-usage (audité : SAFETY, VaultTest)
    if unsafe { exception_check(env) } != 0 {
        return None;
    }
    Some(array)
}
