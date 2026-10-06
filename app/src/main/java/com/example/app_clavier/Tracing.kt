package com.example.app_clavier

import android.os.Trace

/** Perfetto section. The name must be a constant: never put typed text in a trace. */
internal inline fun <T> traced(name:String,block:()->T):T {
    Trace.beginSection(name)
    try{return block()}finally{Trace.endSection()}
}
