package com.example.app_clavier

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

object PendingInput {
    data class Result(val target:String,val text:String?=null,val uri:Uri?=null)
    @Volatile var result:Result?=null
}

class MediaInputActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        if(state!=null)return
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
            PendingInput.result=if(intent.getStringExtra("kind")=="voice")PendingInput.Result(target,text=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()) else PendingInput.Result(target,uri=data?.data)
        };finish()
    }
}
