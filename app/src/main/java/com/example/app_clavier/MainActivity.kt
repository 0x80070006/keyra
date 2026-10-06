package com.example.app_clavier

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*

class MainActivity:Activity(){
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val p=KeyboardPrefs.palette(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(p.background);setPadding(dp(20),dp(20),dp(20),dp(24))}
        if(android.os.Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener{v,insets->val b=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(20),b.top+dp(16),dp(20),b.bottom+dp(24));insets}
        fun text(value:String,size:Float){root.addView(TextView(this).apply{text=value;textSize=size;setTextColor(p.text);setPadding(0,dp(12),0,dp(12))})}
        fun button(value:String,run:()->Unit){root.addView(Button(this).apply{text=value;isAllCaps=false;setTextColor(p.text);backgroundTintList=android.content.res.ColorStateList.valueOf(p.key);setOnClickListener{run()}})}
        text("KEYRA  /  AZERTY",12f)
        text("À ta façon.",34f)
        text("Thèmes • Emoji • Correction française\nSaisie locale, sans compte ni accès réseau.",15f)
        button("1  Activer le clavier"){startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))}
        button("2  Choisir le clavier"){(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()}
        button("Thèmes et correction"){startActivity(Intent(this,SettingsActivity::class.java))}
        text("ESSAYER LE CLAVIER",12f)
        root.addView(EditText(this).apply{
            id=android.view.View.generateViewId();textSize=20f;hint="Écris ici…";setTextColor(p.text);setHintTextColor(p.text);setPadding(dp(14),dp(12),dp(14),dp(12));background=android.graphics.drawable.GradientDrawable().apply{setColor(p.key);cornerRadius=dp(22).toFloat()};minLines=2;maxLines=4;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        },LinearLayout.LayoutParams(-1,dp(100)))
        text("⇧ : majuscules. Appui long sur A–P : chiffres. La flèche en haut à droite ouvre les fonctions. ☺ ouvre les emoji ; ?123 ouvre les symboles, puis la touche 123 ouvre le pavé numérique.",14f)
        text("La correction s’applique à l’espace. Retour arrière immédiatement après une correction restaure le mot d’origine. Règle la tolérance dans les paramètres.",14f)
        setContentView(ScrollView(this).apply{isFillViewport=true;addView(root)})
        root.isFocusableInTouchMode=true;root.requestFocus()
    }
}


