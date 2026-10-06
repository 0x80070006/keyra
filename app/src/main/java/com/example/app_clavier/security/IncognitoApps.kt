package com.example.app_clavier.security

import android.content.Context
import com.example.app_clavier.storage.EncryptedKv

/**
 * Incognito par application (fonction innovante n° 4) : dans ces applications, Keyra n'apprend rien
 * et n'affiche aucun historique (SecurityPolicy.incognitoApp).
 *  - Liste embarquée (KNOWN) : chaque nom de paquet a été vérifié sur Google Play ou F-Droid le 2026-10-06.
 *    Aucune visibilité sur les applications installées (pas de QUERY_ALL_PACKAGES) : on compare au paquet du champ.
 *  - Choix de l'utilisateur dans le magasin chiffré « incognito » (« 1 » ajouté, « 0 » retiré de la liste embarquée).
 *  - Applications où un champ mot de passe a été vu : proposées dans les réglages (magasin « password_apps »).
 * Coffre verrouillé : seule la liste embarquée s'applique.
 */
object IncognitoApps {
    val KNOWN:Set<String> = setOf(
        // Gestionnaires de mots de passe
        "com.x8bit.bitwarden","com.kunzisoft.keepass.free","com.kunzisoft.keepass.libre","proton.android.pass",
        "com.onepassword.android","com.lastpass.lpandroid","com.dashlane","keepass2android.keepass2android_nonet",
        // Messageries chiffrées et navigation anonyme
        "org.thoughtcrime.securesms","ch.threema.app","ch.threema.app.libre","com.wire","ch.protonmail.android","org.torproject.torbrowser",
        // Authentificateurs
        "com.beemdevelopment.aegis","org.fedorahosted.freeotp","com.google.android.apps.authenticator2","com.azure.authenticator","com.authy.authy",
        // Banques courantes en France
        "net.bnpparibas.mescomptes","fr.creditagricole.androidapp","mobi.societegenerale.mobile.lappli","com.fullsix.android.labanquepostale.accountaccess",
        "com.boursorama.android.clients","fr.lcl.android.customerarea","com.cic_prod.bad","com.fortuneo.android",
        "com.revolut.revolut","de.number26.android","com.bunq.android",
    )
    private fun choices(c:Context)=EncryptedKv.of(c,"incognito")
    private fun seen(c:Context)=EncryptedKv.of(c,"password_apps")

    fun isIncognito(c:Context,pkg:String?):Boolean {
        if(pkg.isNullOrEmpty())return false
        return when(choices(c).get(pkg)){"1"->true;"0"->false;else->pkg in KNOWN}
    }
    fun set(c:Context,pkg:String,on:Boolean)=choices(c).put(pkg,if(on)"1" else "0")
    /** Paquets incognito choisis ou embarqués, sauf ceux retirés par l'utilisateur. */
    fun active(c:Context):List<String> {
        val explicit=choices(c).entries() ?: emptyMap()
        return (KNOWN.filter{explicit[it]!="0"}+explicit.filterValues{it=="1"}.keys).distinct().sorted()
    }
    /** Applications où un champ mot de passe a été vu, pas encore en incognito : à proposer. */
    fun suggestions(c:Context)=(seen(c).entries()?.keys ?: emptySet()).filter{!isIncognito(c,it)}.sorted()
    /** Réservé à LearningGate. */
    fun recordPasswordField(c:Context,pkg:String){if(!isIncognito(c,pkg))seen(c).put(pkg,"1")}
    fun clear(c:Context){choices(c).clear();seen(c).clear()}
}
