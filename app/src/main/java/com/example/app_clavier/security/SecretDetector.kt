package com.example.app_clavier.security

import com.example.app_clavier.core.KeyraCore

/**
 * Décide si un texte peut être conservé (appris, gardé dans l'historique).
 * La détection est faite en Rust (keyra_core::secret) ; ce fichier n'ajoute que des règles Kotlin
 * simples et échoue fermé : en cas d'erreur du cœur natif, rien n'est conservé.
 */
object SecretDetector {
    enum class Kind { NONE, CARD_NUMBER, IBAN, ONE_TIME_CODE, API_KEY, HIGH_ENTROPY, EMAIL, PHONE, UNKNOWN }
    private val kinds=Kind.entries

    fun classify(text:CharSequence):Kind {
        if(text.isBlank())return Kind.NONE
        val code=KeyraCore.classify(text)
        return if(code in 0..Kind.PHONE.ordinal)kinds[code] else Kind.UNKNOWN
    }

    /** Un secret, ou un texte que le cœur n'a pas pu analyser : ne jamais l'écrire sur disque. */
    fun isSecret(text:CharSequence)=classify(text).let{it!=Kind.NONE && it!=Kind.EMAIL && it!=Kind.PHONE}

    /** Un mot peut être appris : pas de chiffre, rien de sensible (ni secret ni donnée personnelle). */
    fun isLearnable(word:CharSequence)=word.none{it.isDigit()} && classify(word)==Kind.NONE
}
