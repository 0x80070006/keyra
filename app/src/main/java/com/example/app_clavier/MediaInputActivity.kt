package com.example.app_clavier

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.widget.Toast

/** Résultat de dictée ou d'image, lié au champ d'origine et valable 60 s (constat S9). */
object PendingInput {
    const val MAX_AGE_MS=60_000L
    data class Result(val target:String,val field:Int,val createdAt:Long,val text:String?=null,val uri:Uri?=null)
    @Volatile var result:Result?=null
}

class MediaInputActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        if(state!=null)return
        if(intent.getStringExtra("kind")=="voice" && prefs.getString("voice_warning_ack","")!=recognizers().joinToString()){warnAboutVoice();return}
        launch()
    }
    private val prefs by lazy{KeyboardPrefs.of(this)}
    private fun recognizers()=packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),0)
        .map{"${it.loadLabel(packageManager)} (${it.activityInfo.packageName})"}.distinct().sorted()
    /** Constat S7 : l'audio part vers le service installé, qui peut être en ligne. On le dit avant le premier usage. */
    private fun warnAboutVoice(){
        val names=recognizers()
        val body=if(names.isEmpty())"Aucun service de reconnaissance vocale n’est installé sur ce téléphone." else
            "Keyra n’a pas de reconnaissance vocale intégrée. Ta voix sera confiée à : ${names.joinToString()}.\n\n"+
            "Ce service peut l’envoyer sur Internet selon ses propres règles, ce que Keyra ne peut pas vérifier. "+
            "La dictée reste désactivée dans les champs privés et en navigation privée."
        AlertDialog.Builder(this).setTitle("Dictée vocale").setMessage(body)
            .setPositiveButton("Continuer"){_,_->prefs.edit().putString("voice_warning_ack",names.joinToString()).apply();launch()}
            .setNegativeButton("Annuler"){_,_->finish()}
            .setOnCancelListener{finish()}.show()
    }
    private fun launch(){
        val request=if(intent.getStringExtra("kind")=="voice")Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fr-FR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
        } else Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        try{startActivityForResult(request,40)}catch(e:android.content.ActivityNotFoundException){Toast.makeText(this,"Aucun service compatible installé sur ce téléphone",Toast.LENGTH_LONG).show();finish()}
    }
    @Deprecated("Legacy Activity result bridge for the input method")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==40 && resultCode==RESULT_OK){
            val target=intent.getStringExtra("target") ?: ""
            val field=intent.getIntExtra("field",0);val now=SystemClock.elapsedRealtime()
            PendingInput.result=if(intent.getStringExtra("kind")=="voice")PendingInput.Result(target,field,now,text=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()) else PendingInput.Result(target,field,now,uri=data?.data)
        };finish()
    }
}
