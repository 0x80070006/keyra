package com.example.app_clavier

import android.text.InputType.*
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
import com.example.app_clavier.security.SecurityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {
    private fun policy(type:Int,options:Int=0,pkg:String?="org.example.chat",locked:Boolean=false,incognito:Set<String> =emptySet())=
        SecurityPolicy.of(type,options,pkg,locked,incognito)
    private val text=TYPE_CLASS_TEXT

    @Test fun ordinaryTextLearnsAndSuggests(){
        val p=policy(text)
        assertTrue(p.canSuggest);assertTrue(p.canLearn);assertTrue(p.allowClipboardHistory);assertFalse(p.noHistory)
    }

    @Test fun passwordsBlockEverything(){
        for(type in listOf(text or TYPE_TEXT_VARIATION_PASSWORD,text or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,text or TYPE_TEXT_VARIATION_WEB_PASSWORD,TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD)){
            val p=policy(type)
            assertTrue(p.isPassword);assertFalse(p.canSuggest);assertFalse(p.canLearn);assertTrue(p.noHistory)
            assertFalse(p.allowVoice);assertFalse(p.allowTranslation);assertFalse(p.allowCopy)
        }
        assertTrue(policy(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD).isPinPad)
    }

    /** Constat S1 : un onglet de navigation privée pose ce drapeau, Keyra ne doit plus rien apprendre. */
    @Test fun noPersonalizedLearningKeepsSuggestionsButLearnsNothing(){
        val p=policy(text,IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(p.canSuggest);assertFalse(p.canLearn);assertFalse(p.canUseLearnedWords)
        assertTrue(p.noHistory);assertFalse(p.allowClipboardHistory);assertFalse(p.allowVoice)
    }

    @Test fun incognitoAppsLearnNothing(){
        val p=policy(text,pkg="com.x8bit.bitwarden",incognito=setOf("com.x8bit.bitwarden"))
        assertFalse(p.canLearn);assertTrue(p.noHistory)
        assertTrue(policy(text,pkg="org.example.chat",incognito=setOf("com.x8bit.bitwarden")).canLearn)
    }

    /** Constat S10 : appareil verrouillé, ni apprentissage ni historique. */
    @Test fun lockedDeviceShowsNoHistory(){
        val p=policy(text,locked=true)
        assertFalse(p.canLearn);assertFalse(p.allowClipboardHistory);assertTrue(p.canSuggest)
    }

    @Test fun addressesAndNoSuggestionFieldsAreNotCorrected(){
        assertFalse(policy(text or TYPE_TEXT_VARIATION_EMAIL_ADDRESS).canSuggest)
        assertFalse(policy(text or TYPE_TEXT_VARIATION_URI).canSuggest)
        assertFalse(policy(text or TYPE_TEXT_FLAG_NO_SUGGESTIONS).canLearn)
        assertFalse(policy(TYPE_CLASS_NUMBER).canSuggest)
    }

    @Test fun unknownFieldIsMostRestrictive(){
        assertTrue(SecurityPolicy.NONE.noHistory);assertFalse(SecurityPolicy.NONE.canLearn)
    }
}
