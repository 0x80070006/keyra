package com.example.app_clavier

import android.os.SystemClock

/** Local diagnostic: ACTION_DOWN to the call that writes to the InputConnection. */
object InputLatency {
    @Volatile private var lastDown=0L
    @Volatile private var lastMs=0L
    @Volatile private var totalMs=0L
    @Volatile private var samples=0L
    fun down(){lastDown=SystemClock.uptimeMillis()}
    @Synchronized fun committed(){
        val start=lastDown
        if(start==0L)return
        val elapsed=(SystemClock.uptimeMillis()-start).coerceAtLeast(0)
        lastMs=elapsed;totalMs+=elapsed;samples++
        lastDown=0L
    }
    fun summary():String {
        val count=samples
        if(count==0L)return "Aucune frappe mesurée pour l’instant"
        return "Dernière frappe : ${lastMs} ms · moyenne : ${totalMs/count} ms (${count} frappes)"
    }
}
