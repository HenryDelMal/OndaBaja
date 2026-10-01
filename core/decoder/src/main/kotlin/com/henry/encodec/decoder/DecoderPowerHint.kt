package com.henry.encodec.decoder

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock

internal class DecoderPowerHint(context: Context) : AutoCloseable {
    private val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(PerformanceHintManager::class.java)
    } else {
        null
    }
    private var session: PerformanceHintManager.Session? = null
    private var sessionThreadId = -1
    private var targetWorkNanos = 0L

    fun beginWork(requestedTargetNanos: Long): Long {
        if (manager == null) return SystemClock.uptimeNanos()
        val threadId = Process.myTid()
        runCatching {
            val active = session
            if (active == null) {
                createSession(threadId, requestedTargetNanos)
            } else if (threadId != sessionThreadId) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    active.setThreads(intArrayOf(threadId))
                    sessionThreadId = threadId
                } else {
                    active.close()
                    session = null
                    createSession(threadId, requestedTargetNanos)
                }
            }
            if (session != null && requestedTargetNanos != targetWorkNanos) {
                session?.updateTargetWorkDuration(requestedTargetNanos)
                targetWorkNanos = requestedTargetNanos
            }
        }
        return SystemClock.uptimeNanos()
    }

    fun endWork(startedNanos: Long) {
        val actual = (SystemClock.uptimeNanos() - startedNanos).coerceAtLeast(1L)
        runCatching { session?.reportActualWorkDuration(actual) }
    }

    private fun createSession(threadId: Int, requestedTargetNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        session = manager?.createHintSession(intArrayOf(threadId), requestedTargetNanos)?.also {
            sessionThreadId = threadId
            targetWorkNanos = requestedTargetNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                it.setPreferPowerEfficiency(true)
            }
        }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        sessionThreadId = -1
        targetWorkNanos = 0L
    }
}
