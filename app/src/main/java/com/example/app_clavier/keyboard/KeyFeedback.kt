package com.example.app_clavier.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Retour d'une frappe (phase 6, ADR-0026).
 *  - Haptique « système » : constantes clavier d'Android (KEYBOARD_PRESS puis KEYBOARD_RELEASE), qui suivent
 *    le réglage de retour tactile du téléphone.
 *  - Haptique « personnalisé » : primitive CLICK d'intensité réglable (API 30), sinon impulsion de 12 ms.
 *  - Son : effets système des touches (standard, espace, effacer, entrée), jamais quand le téléphone est en
 *    silencieux ou en vibreur, et **coupé dans les champs sensibles** : le bruit d'Effacer ou d'Entrée
 *    renseigne sur ce qui est tapé.
 * Tout est préparé dans `configure` : rien n'est alloué par frappe.
 */
class KeyFeedback(context:Context){
    enum class Haptic { OFF, SYSTEM, CUSTOM }

    private val audio=context.getSystemService(AudioManager::class.java)
    private val vibrator:Vibrator?=
        if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    private val legacyAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val touchAttributes:Any?=if(Build.VERSION.SDK_INT>=33)VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH) else null
    private var haptic=Haptic.SYSTEM
    private var effect:VibrationEffect?=null
    private var sound=false
    private var volume=0.5f

    fun configure(haptic:Haptic,strength:Float,sound:Boolean,volume:Float,quiet:Boolean){
        this.haptic=haptic
        this.volume=volume.coerceIn(0.05f,1f)
        // Le mode sonore est relu à chaque ouverture du clavier, pas à chaque frappe (appel système).
        this.sound=sound && !quiet && audio?.ringerMode==AudioManager.RINGER_MODE_NORMAL
        effect=if(haptic==Haptic.CUSTOM)customEffect(strength.coerceIn(0.05f,1f)) else null
    }

    private fun customEffect(strength:Float):VibrationEffect? {
        val v=vibrator?.takeIf{it.hasVibrator()} ?: return null
        if(Build.VERSION.SDK_INT>=30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK))
            return VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK,strength).compose()
        val amplitude=if(v.hasAmplitudeControl())(strength*255).toInt().coerceIn(1,255) else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(12,amplitude)
    }

    private fun vibrate(){
        val e=effect ?: return
        val v=vibrator ?: return
        if(Build.VERSION.SDK_INT>=33)v.vibrate(e,touchAttributes as VibrationAttributes)
        else @Suppress("DEPRECATION") v.vibrate(e,legacyAttributes)
    }

    /** À l'appui, avant tout traitement (retour immédiat). */
    fun press(view:View,code:String){
        when(haptic){
            Haptic.SYSTEM->view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
            Haptic.CUSTOM->vibrate()
            Haptic.OFF->{}
        }
        if(sound)audio?.playSoundEffect(soundFor(code),volume)
    }
    /** Au relâchement validé (mode système seulement : le mode personnalisé ne donne qu'un clic). */
    fun release(view:View){if(haptic==Haptic.SYSTEM)view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)}
    /** Pas du pavé tactile ou de la sélection au Retour arrière. */
    fun tick(view:View){
        when(haptic){
            Haptic.SYSTEM->view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Haptic.CUSTOM->vibrate()
            Haptic.OFF->{}
        }
    }
    fun longPress(view:View){
        when(haptic){
            Haptic.SYSTEM->view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Haptic.CUSTOM->vibrate()
            Haptic.OFF->{}
        }
    }

    companion object {
        fun soundFor(code:String)=when(code){
            " "->AudioManager.FX_KEYPRESS_SPACEBAR
            "delete"->AudioManager.FX_KEYPRESS_DELETE
            "enter"->AudioManager.FX_KEYPRESS_RETURN
            else->AudioManager.FX_KEYPRESS_STANDARD
        }
        fun hapticFrom(value:String?)=when(value){"off"->Haptic.OFF;"custom"->Haptic.CUSTOM;else->Haptic.SYSTEM}
    }
}
