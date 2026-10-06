package com.example.app_clavier.security

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Seule source de vérité pour ce que Keyra peut afficher, suggérer ou conserver dans le champ courant.
 * Classe pure (entiers d'EditorInfo en entrée) : testée sur la JVM, voir SecurityPolicyTest.
 *
 * - isPassword : champ mot de passe ou PIN → ni suggestion, ni apprentissage, ni historique.
 * - noLearning : IME_FLAG_NO_PERSONALIZED_LEARNING (navigation privée), application incognito
 *   ou appareil verrouillé → suggestions du dictionnaire embarqué seulement, rien n'est conservé.
 */
class SecurityPolicy private constructor(
    val isPassword:Boolean,
    val isPinPad:Boolean,
    val noPersonalizedLearning:Boolean,
    val noSuggestionsFlag:Boolean,
    val isAddress:Boolean,
    val isText:Boolean,
    val incognitoApp:Boolean,
    val isLocked:Boolean,
){
    /** Rien de ce qui est tapé ici ne doit être conservé, ni montré depuis l'historique. */
    val noHistory get()=isPassword || noPersonalizedLearning || incognitoApp || isLocked
    val canSuggest get()=isText && !isPassword && !noSuggestionsFlag && !isAddress
    val canLearn get()=canSuggest && !noHistory
    val canUseLearnedWords get()=canSuggest && !noHistory
    val allowClipboardHistory get()=!noHistory
    val allowVoice get()=!isPassword && !noPersonalizedLearning && !incognitoApp
    val allowTranslation get()=!isPassword && !noPersonalizedLearning && !incognitoApp
    val allowCopy get()=!isPassword

    companion object {
        private val passwordText=setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        private val addressText=setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI)

        fun of(inputType:Int,imeOptions:Int,packageName:String?,isLocked:Boolean,incognitoApps:Set<String> =emptySet()):SecurityPolicy {
            val cls=inputType and InputType.TYPE_MASK_CLASS
            val variation=inputType and InputType.TYPE_MASK_VARIATION
            val pin=cls==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val text=cls==InputType.TYPE_CLASS_TEXT
            return SecurityPolicy(
                isPassword=(text && variation in passwordText) || pin,
                isPinPad=pin,
                noPersonalizedLearning=imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING!=0,
                noSuggestionsFlag=inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS!=0,
                isAddress=text && variation in addressText,
                isText=text,
                incognitoApp=packageName!=null && packageName in incognitoApps,
                isLocked=isLocked,
            )
        }
        fun of(info:EditorInfo?,isLocked:Boolean,incognitoApps:Set<String> =emptySet())=
            of(info?.inputType ?: 0,info?.imeOptions ?: 0,info?.packageName,isLocked,incognitoApps)
        /** Avant tout champ connu : le plus restrictif. */
        val NONE=of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,0,null,true)
    }
}
