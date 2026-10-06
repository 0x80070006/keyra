package com.example.app_clavier.ime

/**
 * Ce que InputLogic voit du champ de texte. Implémentation Android : RichInputConnection (cache local,
 * aucune lecture IPC pendant la frappe) ; implémentation de test : FakeTarget (JVM).
 * Le texte « avant le curseur » exclut le mot en cours de composition.
 */
interface TextTarget {
    /** Texte avant le curseur (borné à quelques centaines de caractères), sans la composition. */
    fun textBefore():CharSequence
    val hasSelection:Boolean
    fun commit(text:CharSequence)
    /** Remplace la composition (le mot en cours, souligné) ; "" la vide. */
    fun setComposing(text:CharSequence)
    /** Valide la composition telle quelle. */
    fun finishComposing()
    /** Efface `count` points de code avant le curseur (hors composition). */
    fun deleteBefore(count:Int)
    /** Sélectionne les `chars` caractères avant le curseur (0 : rien). */
    fun selectBefore(chars:Int)
    fun deleteSelection()
    fun moveCursor(delta:Int)
    fun batch(block:()->Unit)
}
