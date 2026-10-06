package com.example.app_clavier

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import com.example.app_clavier.engine.BlockedWords
import com.example.app_clavier.engine.NextWords
import com.example.app_clavier.engine.Snippets
import com.example.app_clavier.security.IncognitoApps
import com.example.app_clavier.security.LearningGate
import com.example.app_clavier.security.Panic
import com.example.app_clavier.storage.Backup
import com.example.app_clavier.storage.KeyManager
import java.util.concurrent.Executors

/**
 * Tableau de bord de transparence (fonction innovante n° 2, phase 6) : tout ce que Keyra conserve, à lister,
 * rechercher, modifier et supprimer, plus les deux preuves réseau de D9. FLAG_SECURE : ni capture d'écran,
 * ni aperçu dans les applications récentes. Export et effacement total demandent une confirmation.
 */
class TransparencyActivity:Activity(){
    private companion object {
        const val CREATE_EXPORT=501
        const val OPEN_IMPORT=502
        const val AUTH_FOR_SNIPPET=503
        const val SHOWN=200
    }
    private val prefs by lazy{KeyboardPrefs.of(this)}
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var query=""
    private var vaultOpen=false
    /** Phrase de passe en attente du choix du fichier : effacée dès qu'elle a servi. */
    private var pendingPassword:CharArray?=null
    private var pendingSnippet:Triple<String,String,Boolean>?=null
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vaultOpen=KeyManager.unlock(this)
        render()
    }
    override fun onDestroy(){wipePassword();worker.shutdown();super.onDestroy()}
    private fun wipePassword(){pendingPassword?.fill('\u0000');pendingPassword=null}

    // ------------------------------------------------------------ preuves réseau (D9)
    private fun requestsInternet():Boolean {
        val info=packageManager.getPackageInfo(packageName,PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.contains(Manifest.permission.INTERNET)==true
    }
    private fun sentBytes():String {
        val tx=TrafficStats.getUidTxBytes(Process.myUid())
        return if(tx==TrafficStats.UNSUPPORTED.toLong())getString(R.string.dashboard_tx_unsupported) else getString(R.string.dashboard_tx_bytes,tx)
    }

    private fun render(){
        val p=KeyboardPrefs.palette(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(p.background);setPadding(dp(20),dp(16),dp(20),dp(24))}
        if(android.os.Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener{v,insets->val b=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(20),b.top+dp(12),dp(20),b.bottom+dp(24));insets}
        fun text(s:String,size:Float=15f){root.addView(TextView(this).apply{text=s;textSize=size;setTextColor(p.text);setPadding(0,dp(10),0,dp(6))})}
        fun button(s:String,run:()->Unit){root.addView(Button(this).apply{text=s;isAllCaps=false;setTextColor(p.text);backgroundTintList=android.content.res.ColorStateList.valueOf(p.key);setOnClickListener{run()}})}
        fun row(label:String,action:String,run:()->Unit){
            val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            line.addView(TextView(this).apply{text=label;textSize=14f;setTextColor(p.text)},LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            line.addView(Button(this).apply{text=action;isAllCaps=false;textSize=12f;setTextColor(p.text);backgroundTintList=android.content.res.ColorStateList.valueOf(p.key);setOnClickListener{run()}})
            root.addView(line)
        }
        fun <T> list(items:List<T>,label:(T)->String,action:String,run:(T)->Unit){
            val matching=items.filter{query.isEmpty() || label(it).contains(query,ignoreCase=true)}
            matching.take(SHOWN).forEach{item->row(label(item),action){run(item);render()}}
            if(matching.size>SHOWN)text(getString(R.string.dashboard_more,matching.size-SHOWN),13f)
            if(matching.isEmpty())text(getString(R.string.dashboard_empty),13f)
        }

        button(getString(R.string.back)){finish()}
        text(getString(R.string.dashboard_title),26f)

        text(getString(R.string.dashboard_network),20f)
        text(if(requestsInternet())getString(R.string.dashboard_internet_yes) else getString(R.string.dashboard_internet_no))
        text(sentBytes())
        text(getString(R.string.dashboard_network_note),13f)

        if(!vaultOpen){text(getString(R.string.dashboard_locked));setContentView(ScrollView(this).apply{addView(root)});return}

        root.addView(EditText(this).apply{
            hint=getString(R.string.dashboard_search);setText(query);setTextColor(p.text);setHintTextColor(p.text);setSingleLine()
            setOnEditorActionListener{v,_,_->query=v.text.toString().trim();render();true}
        })

        text(getString(R.string.dashboard_words),20f)
        list(UserLexicon.all(this)?.entries?.sortedByDescending{it.value}.orEmpty(),{"${it.key}  ×${it.value}"},"✕"){UserLexicon.remove(this,it.key)}

        text(getString(R.string.dashboard_pairs),20f)
        list(NextWords.all(this)?.entries?.sortedByDescending{it.value}.orEmpty(),{"${it.key.first} → ${it.key.second}  ×${it.value}"},"✕"){NextWords.remove(this,it.key.first,it.key.second)}

        text(getString(R.string.dashboard_touch_model),20f)
        text(getString(R.string.dashboard_touch_model_none),13f)

        text(getString(R.string.dashboard_clipboard),20f)
        val clips=ClipboardHistory.entries(this)
        list(clips.indices.toList(),{i->(if(clips[i].pinned)"📌 " else "")+clips[i].text.replace('\n',' ').take(80)},"✕"){i->ClipboardHistory.delete(this,i)}

        text(getString(R.string.dashboard_snippets),20f)
        text(getString(R.string.dashboard_snippets_note),13f)
        list(Snippets.all(this).orEmpty(),{s->"${s.shortcut} → "+(s.plain?.replace('\n',' ')?.take(60) ?: "🔒")},"✎"){s->editSnippet(s.shortcut,s.plain.orEmpty(),s.protected)}
        button(getString(R.string.dashboard_snippet_add)){editSnippet("","",false)}

        text(getString(R.string.dashboard_blocked),20f)
        list(BlockedWords.all(this),{it},"✕"){BlockedWords.remove(this,it)}

        text(getString(R.string.dashboard_incognito),20f)
        list(IncognitoApps.active(this),{it},"✕"){IncognitoApps.set(this,it,false)}

        text(getString(R.string.dashboard_backup),20f)
        text(getString(R.string.dashboard_backup_note),13f)
        button(getString(R.string.dashboard_export)){askPassword(confirm=true){pw->
            AlertDialog.Builder(this).setTitle(R.string.dashboard_export).setMessage(R.string.dashboard_export_confirm)
                .setPositiveButton(R.string.dashboard_export){_,_->
                    pendingPassword=pw
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"keyra-export.keyra"),CREATE_EXPORT)
                }.setNegativeButton(R.string.cancel){_,_->pw.fill('\u0000')}.show()
        }}
        button(getString(R.string.dashboard_import)){askPassword(confirm=false){pw->
            pendingPassword=pw
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),OPEN_IMPORT)
        }}

        text(getString(R.string.dashboard_wipe_title),20f)
        button(getString(R.string.dashboard_wipe)){
            AlertDialog.Builder(this).setTitle(R.string.dashboard_wipe).setMessage(R.string.dashboard_wipe_confirm)
                .setPositiveButton(R.string.dashboard_wipe){_,_->Panic.wipe(this,killProcess=false);finishAffinity();Panic.wipe(this)}
                .setNegativeButton(R.string.cancel,null).show()
        }
        setContentView(ScrollView(this).apply{addView(root)})
    }

    private fun editSnippet(shortcut:String,current:String,protected:Boolean){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
        val key=EditText(this).apply{hint=getString(R.string.dashboard_snippet_shortcut);setText(shortcut);setSingleLine()}
        val body=EditText(this).apply{hint=getString(R.string.dashboard_snippet_text);setText(current);minLines=2;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val lock=CheckBox(this).apply{setText(R.string.dashboard_snippet_protect);isChecked=protected}
        box.addView(key);box.addView(body);box.addView(lock)
        val dialog=AlertDialog.Builder(this).setTitle(R.string.dashboard_snippets).setView(box)
            .setPositiveButton(R.string.save){_,_->
                val s=key.text.toString();val t=body.text.toString()
                if(protected && t.isEmpty() && lock.isChecked){render();return@setPositiveButton} // extrait protégé non modifié
                if(shortcut.isNotEmpty() && Snippets.key(shortcut)!=Snippets.key(s))Snippets.remove(this,shortcut)
                saveSnippet(s,t,lock.isChecked)
            }.setNegativeButton(R.string.cancel,null)
        if(shortcut.isNotEmpty())dialog.setNeutralButton(R.string.delete){_,_->Snippets.remove(this,shortcut);render()}
        dialog.show()
    }

    private fun saveSnippet(shortcut:String,text:String,protected:Boolean){
        if(!Snippets.validShortcut(shortcut) || text.isEmpty()){Toast.makeText(this,R.string.dashboard_snippet_invalid,Toast.LENGTH_LONG).show();return}
        if(Snippets.put(this,shortcut,text,protected)){render();return}
        if(protected){
            // La clé exige une authentification récente : on la demande, puis on réessaie.
            pendingSnippet=Triple(shortcut,text,true)
            startActivityForResult(Intent(this,UnlockActivity::class.java).putExtra("mode","auth"),AUTH_FOR_SNIPPET)
        }else Toast.makeText(this,R.string.dashboard_locked,Toast.LENGTH_LONG).show()
    }

    private fun askPassword(confirm:Boolean,then:(CharArray)->Unit){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
        fun field(hint:Int)=EditText(this).apply{setHint(hint);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}.also{box.addView(it)}
        val first=field(R.string.dashboard_password)
        val second=if(confirm)field(R.string.dashboard_password_again) else null
        AlertDialog.Builder(this).setTitle(R.string.dashboard_password).setMessage(if(confirm)R.string.dashboard_password_rules else R.string.dashboard_password_import).setView(box)
            .setPositiveButton(R.string.ok){_,_->
                val a=first.text;val b=second?.text
                val ok=a.length>=MIN_PASSWORD && (b==null || a.toString()==b.toString())
                val chars=CharArray(a.length){a[it]}
                a.clear();b?.clear()
                if(ok)then(chars) else {chars.fill('\u0000');Toast.makeText(this,R.string.dashboard_password_bad,Toast.LENGTH_LONG).show()}
            }.setNegativeButton(R.string.cancel,null).show()
    }

    @Deprecated("Sélecteur de documents et invite d'authentification")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==AUTH_FOR_SNIPPET){
            val s=pendingSnippet;pendingSnippet=null
            if(resultCode==RESULT_OK && s!=null && Snippets.put(this,s.first,s.second,true))render()
            else Toast.makeText(this,R.string.unlock_failed,Toast.LENGTH_SHORT).show()
            return
        }
        val pw=pendingPassword;pendingPassword=null
        val uri=data?.data
        if(pw==null || resultCode!=RESULT_OK || uri==null){pw?.fill('\u0000');return}
        Toast.makeText(this,R.string.dashboard_working,Toast.LENGTH_SHORT).show()
        when(requestCode){
            CREATE_EXPORT->worker.execute{
                val bytes=try{Backup.export(this,pw)}finally{pw.fill('\u0000')}
                val ok=bytes!=null && runCatching{contentResolver.openOutputStream(uri,"wt")!!.use{it.write(bytes)};true}.getOrDefault(false)
                main.post{if(ok)Toast.makeText(this,R.string.dashboard_export_done,Toast.LENGTH_LONG).show() else Toast.makeText(this,R.string.dashboard_export_failed,Toast.LENGTH_LONG).show()}
            }
            OPEN_IMPORT->worker.execute{
                val data=runCatching{contentResolver.openInputStream(uri)!!.use{input->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);if(out.size()>MAX_IMPORT)break}
                    out.toByteArray()
                }}.getOrNull()
                val (result,payload)=if(data==null || data.size>MAX_IMPORT){pw.fill('\u0000');Backup.Result.BAD_FILE to null} else try{Backup.open(pw,data)}finally{pw.fill('\u0000')}
                val written=payload!=null && LearningGate.restore(this,payload)
                main.post{
                    when{
                        written->Toast.makeText(this,R.string.dashboard_import_done,Toast.LENGTH_LONG).show()
                        result==Backup.Result.WRONG_PASSWORD->Toast.makeText(this,R.string.dashboard_import_password,Toast.LENGTH_LONG).show()
                        result==Backup.Result.NOT_AN_EXPORT || result==Backup.Result.BAD_FILE->Toast.makeText(this,R.string.dashboard_import_bad,Toast.LENGTH_LONG).show()
                        else->Toast.makeText(this,R.string.dashboard_import_failed,Toast.LENGTH_LONG).show()
                    }
                    render()
                }
            }
        }
    }
}

private const val MIN_PASSWORD=10
private const val MAX_IMPORT=16*1024*1024
