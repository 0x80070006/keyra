package com.example.app_clavier

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.example.app_clavier.security.IncognitoApps
import com.example.app_clavier.security.Panic
import com.example.app_clavier.storage.KeyManager
import com.example.app_clavier.storage.Migration11to12

class SettingsActivity:Activity(){
    private companion object { const val pickBackground=410 }
    private val prefs by lazy{KeyboardPrefs.of(this)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    // Affiche les mots personnels : jamais dans les captures ni dans la vue des applications récentes (S6).
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        // Réglages ouverts : l'appareil est déverrouillé, on ouvre le coffre (une opération Keystore).
        vaultOpen=KeyManager.unlock(this);if(vaultOpen)Migration11to12.run(this)
        render()
    }
    private var vaultOpen=false
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data ?: return
        if(requestCode==pickBackground && resultCode==RESULT_OK){
            runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            prefs.edit().putString("background_uri",uri.toString()).putString("theme","image").apply()
            render()
        }
    }
    private fun render(){
        val p=KeyboardPrefs.palette(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(p.background);setPadding(dp(20),dp(16),dp(20),dp(24))}
        if(android.os.Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener{v,insets->val b=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(20),b.top+dp(12),dp(20),b.bottom+dp(24));insets}
        fun text(s:String,size:Float=16f){root.addView(TextView(this).apply{text=s;textSize=size;setTextColor(p.text);setPadding(0,dp(12),0,dp(8))})}
        fun button(s:String,run:()->Unit){root.addView(Button(this).apply{text=s;isAllCaps=false;setTextColor(p.text);backgroundTintList=android.content.res.ColorStateList.valueOf(p.key);setOnClickListener{run()}})}
        button("‹  Retour"){finish()}
        text("Ton clavier, tes réglages",27f)
        text("Correction française",21f)
        root.addView(Switch(this).apply{text="Corriger automatiquement à l’espace";setTextColor(p.text);isChecked=prefs.getBoolean("correction",true);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("correction",v).apply()}})
        val value=TextView(this).apply{setTextColor(p.text);textSize=16f;setPadding(0,dp(14),0,0)}
        fun toleranceLabel(v:Int)="Tolérance : $v / 100 — "+when{v==0->"suggestions seules";v<35->"prudent";v<70->"équilibré";else->"plus tolérant"}
        value.text=toleranceLabel(prefs.getInt("tolerance",55));root.addView(value)
        root.addView(SeekBar(this).apply{max=100;progress=prefs.getInt("tolerance",55);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){value.text=toleranceLabel(v);if(user)prefs.edit().putInt("tolerance",v).apply()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })})
        text("Prudent exige une forte confiance. Plus tolérant accepte davantage d’erreurs, dont deux lettres sur les mots longs. Les mots déjà reconnus, les noms commençant par une majuscule, les adresses et les mots de passe ne sont pas corrigés. Ce correcteur traite l’orthographe, pas la grammaire.",14f)
        text("Mots personnels à conserver",18f)
        val personal=EditText(this).apply{hint="Un mot par ligne";setTextColor(p.text);setHintTextColor(p.text);minLines=2;maxLines=5;setText(PersonalWords.asText(this@SettingsActivity));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        root.addView(personal);button("Enregistrer mes mots"){if(PersonalWords.replaceFromText(this,personal.text.toString()))Toast.makeText(this,"Mots enregistrés, chiffrés, sur ce téléphone",Toast.LENGTH_SHORT).show() else Toast.makeText(this,"Coffre fermé : déverrouille le téléphone",Toast.LENGTH_SHORT).show()}
        text("Saisie et réactivité",21f)
        root.addView(Switch(this).apply{text="Retour haptique à chaque touche";setTextColor(p.text);isChecked=prefs.getBoolean("haptic",true);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("haptic",v).apply()}})
        root.addView(Switch(this).apply{text="Deux espaces insèrent un point";setTextColor(p.text);isChecked=prefs.getBoolean("double_space_period",true);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("double_space_period",v).apply()}})
        root.addView(Switch(this).apply{setText(R.string.setting_commit_on_down);setTextColor(p.text);isChecked=prefs.getBoolean("commit_on_down",false);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("commit_on_down",v).apply()}})
        text("Par défaut, la touche est validée au relâchement : tu peux glisser le doigt pour corriger une frappe imprécise, ou glisser depuis ?123 vers un symbole. Un second doigt valide aussitôt la frappe du premier.",14f)
        val longPress=TextView(this).apply{setTextColor(p.text);textSize=16f;setPadding(0,dp(14),0,0)}
        fun longPressLabel(v:Int)="Appui long (accents, chiffres) : $v ms"
        longPress.text=longPressLabel(prefs.getInt("long_press_ms",300));root.addView(longPress)
        root.addView(SeekBar(this).apply{max=40;progress=(prefs.getInt("long_press_ms",300)-200)/10;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){val ms=200+v*10;longPress.text=longPressLabel(ms);if(user)prefs.edit().putInt("long_press_ms",ms).apply()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })})
        root.addView(Switch(this).apply{setText(R.string.setting_pin_shuffle);setTextColor(p.text);isChecked=prefs.getBoolean("pin_shuffle",false);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("pin_shuffle",v).apply()}})
        text("Écriture",18f)
        fun toggle(res:Int,key:String,default:Boolean)=root.addView(Switch(this).apply{setText(res);setTextColor(p.text);isChecked=prefs.getBoolean(key,default);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(key,v).apply()}})
        toggle(R.string.setting_auto_cap,"auto_cap",true)
        toggle(R.string.setting_composing,"composing",true)
        text("Espace avant « ? ! ; : » (typographie française)",15f)
        val spaceRow=LinearLayout(this)
        listOf("fine" to "Fine insécable","insecable" to "Insécable","aucune" to "Aucune").forEach{(id,title)->spaceRow.addView(Button(this).apply{
            text=if(prefs.getString("fr_space","fine")==id)"✓ $title" else title;isAllCaps=false;textSize=12f;setTextColor(p.text)
            backgroundTintList=android.content.res.ColorStateList.valueOf(p.key)
            setOnClickListener{prefs.edit().putString("fr_space",id).apply();render()}
        },LinearLayout.LayoutParams(0,dp(56),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
        root.addView(spaceRow)
        text("Gestes",18f)
        toggle(R.string.setting_trackpad,"trackpad",true)
        toggle(R.string.setting_gesture_down,"gesture_down",true)
        toggle(R.string.setting_gesture_up,"gesture_up",true)
        toggle(R.string.setting_gesture_left,"gesture_left",false)
        text("Retour arrière : maintenu, il accélère puis efface mot par mot ; glissé vers la gauche, il sélectionne des mots à effacer.",14f)
        text("Dans les champs de code PIN, les chiffres changent de place à chaque fois : quelqu’un qui regarde tes doigts ne peut pas deviner le code. Les champs privés n’affichent jamais la touche enfoncée.",14f)
        val latency=TextView(this).apply{setTextColor(p.text);textSize=16f;setPadding(0,dp(14),0,0)}
        fun latencyLabel(v:Int)="Objectif de latence : ≤ ${v} ms — ACTION_DOWN jusqu’à l’envoi du caractère"
        latency.text=latencyLabel(prefs.getInt("latency_target",50));root.addView(latency)
        root.addView(SeekBar(this).apply{max=90;progress=prefs.getInt("latency_target",50)-10;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){val target=v+10;latency.text=latencyLabel(target);if(user)prefs.edit().putInt("latency_target",target).apply()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){Toast.makeText(this@SettingsActivity,InputLatency.summary(),Toast.LENGTH_SHORT).show()} // journal-ok: durées seulement, aucun texte tapé
        })})
        text("Le clavier vise 50 ms par défaut. Le diagnostic mesure le chemin local de l’appui à l’appel d’écriture dans le champ ; l’application qui reçoit le texte peut ensuite ajouter son propre délai.",14f)
        text("Apprentissage local",18f)
        text("Les mots inconnus saisis puis validés au moins deux fois peuvent apparaître dans les suggestions. Ils restent dans les données privées de Keyra et ne sont jamais envoyés sur Internet.",14f)
        button("Effacer les mots appris"){UserLexicon.clear(this);Toast.makeText(this,"Mots appris effacés",Toast.LENGTH_SHORT).show()}
        text("Traduction hors ligne",21f)
        text("Le panneau Traduction travaille uniquement avec le lexique FR ↔ EN embarqué. Sélectionne un texte ou place le curseur après une phrase, ouvre le menu du clavier, puis touche Traduction hors ligne. Le bouton Remplacer substitue le texte traduit à la sélection ou à la phrase. Les mots inconnus restent inchangés et aucun texte n’est envoyé hors du téléphone.",14f)
        text("Thèmes",21f)
        KeyboardPrefs.themes.entries.chunked(2).forEach{pair->
            val row=LinearLayout(this)
            pair.forEach{(id,title)->val colors=KeyboardPrefs.palette(this,id);row.addView(Button(this).apply{text=if(prefs.getString("theme","brown")==id)"✓ $title" else title;isAllCaps=false;textSize=12f;setTextColor(colors.text);background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(colors.key,colors.special)).apply{cornerRadius=dp(16).toFloat()};setOnClickListener{prefs.edit().putString("theme",id).apply();render()}},LinearLayout.LayoutParams(0,dp(78),1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})}
            root.addView(row)
        }
        text("Couleurs du téléphone reprend deux palettes Android différentes : les lettres et les touches spéciales. La palette est relue à chaque ouverture du clavier.",14f)
        val first=EditText(this).apply{setText(prefs.getString("color1","#472118"));hint="Couleur des lettres #RRGGBB";setTextColor(p.text);setSingleLine()}
        val second=EditText(this).apply{setText(prefs.getString("color2","#12292F"));hint="Touches spéciales #RRGGBB";setTextColor(p.text);setSingleLine()}
        root.addView(first);root.addView(second)
        button("Appliquer mes deux couleurs"){
            val a=first.text.toString().trim();val b=second.text.toString().trim()
            if(a.matches(Regex("#[0-9a-fA-F]{6}")) && b.matches(Regex("#[0-9a-fA-F]{6}"))){prefs.edit().putString("color1",a).putString("color2",b).putString("theme","custom").apply();render()}
            else Toast.makeText(this,"Utilise deux couleurs au format #RRGGBB",Toast.LENGTH_LONG).show()
        }
        text("Image personnalisée",21f)
        text("L’image reste sur le téléphone. Le fond et les touches utilisent deux flous gaussiens indépendants, avec un flou plus fort sur les touches pour garder les lettres lisibles.",14f)
        button("Choisir une image de fond"){
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),pickBackground)
        }
        fun blurControl(title:String,pref:String,default:Int){
            val value=TextView(this).apply{setTextColor(p.text);textSize=15f;text="$title : ${prefs.getInt(pref,default)}"}
            root.addView(value)
            root.addView(SeekBar(this).apply{max=25;progress=prefs.getInt(pref,default);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){value.text="$title : $v"}
                override fun onStartTrackingTouch(s:SeekBar?){}
                override fun onStopTrackingTouch(s:SeekBar?){prefs.edit().putInt(pref,progress).apply()}
            })})
        }
        blurControl("Flou du fond","background_blur",8)
        blurControl("Flou des touches","key_blur",18)
        button("Retirer l’image personnalisée"){prefs.edit().remove("background_uri").putString("theme","brown").apply();render()}
        text("Coffre chiffré",21f)
        text(if(vaultOpen)"Mots appris, mots personnels, emoji récents, presse-papiers et choix incognito sont chiffrés (XChaCha20-Poly1305). La clé de données est enveloppée par une clé du Keystore Android de niveau : ${KeyManager.securityLevel()}, inutilisable quand le téléphone est verrouillé. Elle est effacée de la mémoire dès que l’écran s’éteint."
            else "Coffre fermé : déverrouille le téléphone pour gérer tes données.",14f)
        text("Presse-papiers éphémère",18f)
        val ttlChoices=listOf(5 to "5 minutes",60 to "1 heure",1440 to "1 jour",0 to "jamais")
        val ttlRow=LinearLayout(this)
        ttlChoices.forEach{(minutes,title)->ttlRow.addView(Button(this).apply{
            text=if(prefs.getInt("clip_ttl_min",60)==minutes)"✓ $title" else title;isAllCaps=false;textSize=12f;setTextColor(p.text)
            backgroundTintList=android.content.res.ColorStateList.valueOf(p.key)
            setOnClickListener{prefs.edit().putInt("clip_ttl_min",minutes).apply();render()}
        },LinearLayout.LayoutParams(0,dp(56),1f).apply{setMargins(dp(2),dp(2),dp(2),dp(2))})}
        root.addView(ttlRow)
        text("Durée de vie d’une copie dans l’historique (les copies épinglées restent). Une copie sensible — mot de passe, carte, IBAN, code, clé — n’est jamais enregistrée.",14f)
        root.addView(Switch(this).apply{setText(R.string.setting_clear_sensitive);setTextColor(p.text);isChecked=prefs.getBoolean("clear_sensitive_clip",false);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("clear_sensitive_clip",v).apply()}})
        text("Applications incognito",18f)
        text("Dans ces applications, Keyra n’apprend rien et ne montre aucun historique. La liste de départ (gestionnaires de mots de passe, messageries chiffrées, authentificateurs, banques) est vérifiée ; Keyra ne voit pas les applications installées.",14f)
        IncognitoApps.active(this).forEach{pkg->button("✓ $pkg — retirer"){IncognitoApps.set(this,pkg,false);render()}}
        IncognitoApps.suggestions(this).forEach{pkg->button("+ $pkg (champ mot de passe vu) — ajouter"){IncognitoApps.set(this,pkg,true);render()}}
        text("Geste panique",18f)
        text("Appui de 3 secondes sur la touche menu du clavier, puis glisser pour confirmer. Ou ce bouton :",14f)
        button("Tout effacer maintenant"){
            AlertDialog.Builder(this).setTitle("Tout effacer ?").setMessage("Mots appris, mots personnels, emoji, presse-papiers et choix incognito seront détruits définitivement. Les réglages d’apparence sont conservés.")
                .setPositiveButton("Effacer"){_,_->Panic.wipe(this,killProcess=false);finishAffinity();Panic.wipe(this)}
                .setNegativeButton("Annuler",null).show()
        }
        text("Données libres et confidentialité",21f)
        text("Lexique français FrequencyWords (50 000 entrées source, CC BY-SA 4.0). Recherche locale par distance Damerau-Levenshtein, complétion et fréquence d’usage. Catalogue Unicode Emoji 17.0 : 3 944 séquences complètes avec variantes. La traduction légère FR ↔ EN, les suggestions et les flous d’image s’exécutent localement. Les données apprises sont chiffrées dans les données privées de l’application, sans sauvegarde Android ni transfert vers un autre appareil. Le presse-papiers est observé pendant que le clavier est ouvert ; les champs privés et les copies sensibles sont exclus. Aucun historique des frappes ni accès réseau.",14f)
        button("Effacer les copies enregistrées"){ClipboardHistory.clear(this);Toast.makeText(this,"Copies effacées",Toast.LENGTH_SHORT).show()}
        button("Effacer les emoji fréquents"){EmojiHistory.clear(this);Toast.makeText(this,"Historique des emoji effacé",Toast.LENGTH_SHORT).show()}
        button("Sources et licences"){
            val names=assets.list("licenses")!!.sorted()
            AlertDialog.Builder(this).setTitle("Sources et licences").setItems(names.toTypedArray()){_,i->
                val body=assets.open("licenses/${names[i]}").bufferedReader().use{it.readText()}
                val v=TextView(this).apply{text=body;textSize=12f;setPadding(dp(16),dp(12),dp(16),dp(12))}
                AlertDialog.Builder(this).setTitle(names[i]).setView(ScrollView(this).apply{addView(v)}).setPositiveButton("Fermer",null).show()
            }.setNegativeButton("Fermer",null).show()
        }
        setContentView(ScrollView(this).apply{addView(root)})
    }
}
