package com.example.app_clavier.ime

import android.content.SharedPreferences

/**
 * Instantané immuable des réglages de saisie : relu seulement quand une préférence change,
 * jamais à chaque frappe (constat R8 et R11 de la phase 0).
 */
data class ImeSettings(
    val autocorrect:Boolean=true,
    val tolerance:Int=55,
    val doubleSpacePeriod:Boolean=true,
    val autoCap:Boolean=true,
    /** Espace avant « ? ! ; : » en français : fine insécable, insécable, ou rien (null). */
    val frenchSpace:Char?=NARROW_NBSP,
    val composing:Boolean=true,
){
    companion object {
        const val NARROW_NBSP=' '
        const val NBSP=' '
        fun from(p:SharedPreferences)=ImeSettings(
            autocorrect=p.getBoolean("correction",true),
            tolerance=p.getInt("tolerance",55),
            doubleSpacePeriod=p.getBoolean("double_space_period",true),
            autoCap=p.getBoolean("auto_cap",true),
            frenchSpace=when(p.getString("fr_space","fine")){"fine"->NARROW_NBSP;"insecable"->NBSP;else->null},
            composing=p.getBoolean("composing",true),
        )
    }
}
