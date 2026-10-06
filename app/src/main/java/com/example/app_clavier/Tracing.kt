package com.example.app_clavier

import android.os.Trace

/** Perfetto section. The name must be a constant: never put typed text in a trace. */
inline fun <T> traced(name:String,block:()->T):T {
    Trace.beginSection(name) // journal-ok: seuls des littéraux sont passés à traced(), vérifié par logGuard
    try{return block()}finally{Trace.endSection()}
}
