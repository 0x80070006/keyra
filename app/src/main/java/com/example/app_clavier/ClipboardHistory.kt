package com.example.app_clavier

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray

/** Stores only explicit plain text clips, in app-private storage, without Android backup. */
object ClipboardHistory {
    private const val MAX_ITEMS = 20
    private const val MAX_CHARS = 10_000
    private fun prefs(context:Context)=context.getSharedPreferences("clipboard_history",Context.MODE_PRIVATE)
    fun items(context:Context):List<String> = runCatching {
        val json=JSONArray(prefs(context).getString("items","[]"))
        (0 until json.length()).map{json.getString(it)}
    }.getOrDefault(emptyList())
    fun clear(context:Context)=prefs(context).edit().clear().apply()
    fun capture(context:Context,manager:ClipboardManager) {
        val description=manager.primaryClipDescription ?: return
        if(Build.VERSION.SDK_INT>=33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false)==true)return
        val clip=manager.primaryClip ?: return
        if(clip.itemCount<1)return
        val value=clip.getItemAt(0).text?.toString()?.take(MAX_CHARS)?.takeIf{it.isNotBlank()} ?: return
        val updated=ArrayList<String>(MAX_ITEMS)
        updated.add(value)
        items(context).filterTo(updated){it!=value && updated.size<MAX_ITEMS}
        val json=JSONArray();updated.forEach(json::put)
        prefs(context).edit().putString("items",json.toString()).apply()
    }
}
