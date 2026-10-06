//! Cœur de Keyra : fonctions pures, déterministes, sans entrée-sortie ni code `unsafe`.
//!
//! Tout ce qui touche à Android (JNI, Keystore, `InputConnection`) reste hors de ce crate.
#![forbid(unsafe_code)]

pub mod backup;
pub mod predict;
pub mod secret;
pub mod vault;
