//! Pont JNI minimal vers `keyra-core`, pour `com.example.app_clavier.core.KeyraCore`.
//!
//! Règles de ce crate :
//! - c'est le **seul** endroit autorisé à contenir du code `unsafe` ; chaque bloc porte un commentaire `SAFETY:` ;
//! - Kotlin passe du texte en `byte[]` UTF-8 (pas de `String` Java : pas d'UTF-8 modifié, et le tampon peut être effacé) ;
//! - toute erreur renvoie [`ERROR`] et l'appelant Kotlin doit alors considérer le texte comme secret ;
//! - aucune panique ne traverse la frontière (`catch_unwind`) ;
//! - la copie Rust du texte est effacée (`zeroize`) avant de rendre la main.
//!
//! Les tests de ce pont sont des tests instrumentés Android (`KeyraCoreTest`) : ils passent par la vraie JVM.

use jni_sys::{JNIEnv, jbyte, jbyteArray, jclass, jint, jsize};
use std::panic::{AssertUnwindSafe, catch_unwind};
use zeroize::Zeroize;

/// Valeur renvoyée en cas d'échec : entrée nulle, trop grande, non UTF-8, exception JVM ou panique.
pub const ERROR: jint = -1;

/// Version du contrat entre la bibliothèque native et `KeyraCore.kt`. À incrémenter à chaque changement de signature.
pub const ABI_VERSION: jint = 1;

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
    let Some(mut buffer) = copy_byte_array(env, input) else {
        return ERROR;
    };
    let code = match std::str::from_utf8(&buffer) {
        Ok(text) => keyra_core::secret::classify(text).code(),
        Err(_) => ERROR,
    };
    buffer.zeroize();
    code
}

/// Copie un `byte[]` Java dans un `Vec<u8>` borné par `MAX_INPUT_BYTES`.
fn copy_byte_array(env: *mut JNIEnv, array: jbyteArray) -> Option<Vec<u8>> {
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
    if length > keyra_core::secret::MAX_INPUT_BYTES {
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
