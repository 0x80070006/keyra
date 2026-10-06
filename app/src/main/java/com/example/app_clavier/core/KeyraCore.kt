package com.example.app_clavier.core

/**
 * Façade JNI du cœur Rust (rust/keyra-jni). Contrat : rust/keyra-jni/src/lib.rs.
 * Le texte est passé en UTF-8 dans un ByteArray, effacé après l'appel ; toute erreur
 * renvoie ERROR et l'appelant doit alors « échouer fermé » (traiter le texte comme secret).
 */
internal object KeyraCore {
    const val ERROR=-1
    private const val ABI_VERSION=1
    /** Faux si la bibliothèque native manque ou ne correspond pas à ce code Kotlin. */
    val available:Boolean by lazy {
        runCatching{System.loadLibrary("keyra_jni");abiVersion()==ABI_VERSION}.getOrDefault(false)
    }
    @JvmStatic private external fun abiVersion():Int
    @JvmStatic private external fun classify(utf8:ByteArray):Int

    /** Code de keyra_core::secret::Sensitivity, ou ERROR. */
    fun classify(text:CharSequence):Int {
        if(!available)return ERROR
        val bytes=text.toString().toByteArray(Charsets.UTF_8)
        return try{classify(bytes)}catch(_:Throwable){ERROR}finally{bytes.fill(0)}
    }
}
