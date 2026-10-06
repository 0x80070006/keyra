package com.example.app_clavier

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.widget.Toast
import com.example.app_clavier.engine.Snippets
import com.example.app_clavier.storage.KeyManager

/**
 * Activité transparente qui affiche l'invite biométrique pour un extrait protégé (ADR-0029), sur le modèle de
 * MediaInputActivity : un IME ne peut pas afficher lui-même l'invite de façon fiable. Après succès, l'extrait
 * est déchiffré et confié à PendingInput, lié au champ d'origine et valable 60 s ; le clavier l'insère quand ce
 * champ reprend le focus.
 * Extra « mode » : « snippet » (insérer l'extrait « shortcut ») ou « auth » (déverrouiller la clé pendant 30 s,
 * pour enregistrer un extrait protégé depuis le tableau de transparence).
 */
class UnlockActivity:Activity(){
    private val cancel=CancellationSignal()

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if(state!=null){finish();return}
        val builder=BiometricPrompt.Builder(this).setTitle(getString(R.string.unlock_title)).setSubtitle(getString(R.string.unlock_subtitle))
        if(Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        builder.build().authenticate(cancel,mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult?){done()}
            override fun onAuthenticationError(code:Int,message:CharSequence?){finish()}
        })
    }

    private fun done(){
        if(intent.getStringExtra("mode")=="snippet"){
            // L'activité est au premier plan : l'appareil est déverrouillé, le coffre peut s'ouvrir.
            if(!KeyManager.unlock(this)){finish();return}
            val snippet=Snippets.find(this,intent.getStringExtra("shortcut").orEmpty())
            val text=snippet?.let(Snippets::open)
            if(text==null)Toast.makeText(this,R.string.unlock_failed,Toast.LENGTH_SHORT).show()
            else PendingInput.result=PendingInput.Result(intent.getStringExtra("target").orEmpty(),intent.getIntExtra("field",0),SystemClock.elapsedRealtime(),text=text)
        }else setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy(){cancel.cancel();super.onDestroy()}
}
