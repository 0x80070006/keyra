package com.example.app_clavier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object EmojiHistory {
    data class Usage(val emoji:String,val count:Int,val last:Long)
    private val writer=Executors.newSingleThreadScheduledExecutor()
    private var pending:ScheduledFuture<*>?=null
    private var cached:MutableList<Usage>?=null
    private fun prefs(c:Context)=c.getSharedPreferences("emoji_history",Context.MODE_PRIVATE)
    private fun read(c:Context):MutableList<Usage> = runCatching {
        val array=JSONArray(prefs(c).getString("usage","[]"))
        (0 until array.length()).map {val o=array.getJSONObject(it);Usage(o.getString("emoji"),o.getInt("count"),o.getLong("last"))}
    }.getOrDefault(emptyList()).toMutableList()
    private fun entries(c:Context)=cached ?: read(c).also{cached=it}
    @Synchronized fun top(c:Context,allowed:Set<String>?=null):List<String> = entries(c).asSequence()
        .filter{allowed==null || it.emoji in allowed}
        .sortedWith(compareByDescending<Usage>{it.count}.thenByDescending{it.last})
        .take(18).map{it.emoji}.toList()
    @Synchronized fun record(c:Context,emoji:String) {
        val all=entries(c);val old=all.find{it.emoji==emoji};all.removeAll{it.emoji==emoji}
        all.add(Usage(emoji,((old?.count ?: 0)+1).coerceAtMost(1_000_000),System.currentTimeMillis()))
        if(all.size>256)all.removeAll(all.sortedByDescending{it.last}.drop(256).toSet())
        val app=c.applicationContext
        pending?.cancel(false)
        pending=writer.schedule({
            val snapshot=synchronized(this){entries(app).toList()}
            val array=JSONArray()
            snapshot.sortedByDescending{it.last}.forEach{array.put(JSONObject().put("emoji",it.emoji).put("count",it.count).put("last",it.last))}
            prefs(app).edit().putString("usage",array.toString()).apply()
        },200,TimeUnit.MILLISECONDS)
    }
    @Synchronized fun clear(c:Context){cached=mutableListOf();pending?.cancel(false);val app=c.applicationContext;pending=writer.schedule({prefs(app).edit().clear().apply()},0,TimeUnit.MILLISECONDS)}
}
