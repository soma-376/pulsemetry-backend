package com.team376.pulsemetry.telemetry.diagnostics

import java.util.Collections
import java.util.IdentityHashMap

/** 예외 메시지·suppressed 원문 없이 타입과 제한된 코드 위치만 출력한다(ADR 0068). */
internal object SafeDiagnostic {
    fun of(failure: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current) && chain.size < 8) {
            chain += current
            current = current.cause
        }
        return chain.joinToString(" <- ") { cause ->
            val type = cause.javaClass.name
            val frames = cause.stackTrace.take(8).joinToString(",") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
            "$type[$frames]"
        }.take(4096)
    }
}
