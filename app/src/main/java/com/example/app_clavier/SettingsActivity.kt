package com.example.app_clavier

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class SettingsActivity:Activity(){
    private companion object { const val pickBackground=410 }
    private val prefs by lazy{KeyboardPrefs.of(this)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?){super.onCreate(state);render()}
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
        val personal=EditText(this).apply{hint="Un mot par ligne";setTextColor(p.text);setHintTextColor(p.text);minLines=2;maxLines=5;setText(prefs.getString("personal",""));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        root.addView(personal);button("Enregistrer mes mots"){prefs.edit().putString("personal",personal.text.toString()).apply();Toast.makeText(this,"Mots enregistrés sur ce téléphone",Toast.LENGTH_SHORT).show()}
        text("Saisie et réactivité",21f)
        root.addView(Switch(this).apply{text="Retour haptique à chaque touche";setTextColor(p.text);isChecked=prefs.getBoolean("haptic",true);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("haptic",v).apply()}})
        root.addView(Switch(this).apply{text="Deux espaces insèrent un point";setTextColor(p.text);isChecked=prefs.getBoolean("double_space_period",true);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean("double_space_period",v).apply()}})
        val latency=TextView(this).apply{setTextColor(p.text);textSize=16f;setPadding(0,dp(14),0,0)}
        fun latencyLabel(v:Int)="Objectif de latence : ≤ ${v} ms — ACTION_DOWN jusqu’à l’envoi du caractère"
        latency.text=latencyLabel(prefs.getInt("latency_target",50));root.addView(latency)
        root.addView(SeekBar(this).apply{max=90;progress=prefs.getInt("latency_target",50)-10;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){val target=v+10;latency.text=latencyLabel(target);if(user)prefs.edit().putInt("latency_target",target).apply()}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){Toast.makeText(this@SettingsActivity,InputLatency.summary(),Toast.LENGTH_SHORT).show()}
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
        text("Données libres et confidentialité",21f)
        text("Lexique français FrequencyWords (50 000 entrées source, CC BY-SA 4.0). Recherche locale par distance Damerau-Levenshtein, complétion et fréquence d’usage. Catalogue Unicode Emoji 17.0 : 3 944 séquences complètes avec variantes. La traduction légère FR ↔ EN, les suggestions et les flous d’image s’exécutent localement. Les fréquences des emoji et les 20 dernières copies de texte sont conservées dans les données privées de l’application, sans sauvegarde Android. Le presse-papiers est observé pendant que le clavier est ouvert ; les champs privés et les copies marquées sensibles sont exclus. Aucun historique des frappes ni accès réseau.",14f)
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
