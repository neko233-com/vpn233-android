package com.neko233.vpn233.service

import kotlinx.coroutines.sync.Mutex

/** Coalesces lifecycle requests while preserving the latest requested running state. */
internal class LifecycleRequests {
    enum class Command { START, RELOAD, STOP }
    enum class Work { LOAD, STOP }

    private var desiredRunning = false
    private var pending = false
    private var workerRunning = false

    @Synchronized
    fun request(command: Command, onAccepted: () -> Unit = {}): Boolean {
        when (command) {
            Command.START -> desiredRunning = true
            Command.STOP -> desiredRunning = false
            Command.RELOAD -> if (!desiredRunning) return false
        }
        pending = true
        onAccepted()
        if (workerRunning) return false
        workerRunning = true
        return true
    }

    @Synchronized
    fun next(): Work? {
        if (!pending) {
            workerRunning = false
            return null
        }
        pending = false
        return if (desiredRunning) Work.LOAD else Work.STOP
    }

    @Synchronized
    fun hasPendingStart(): Boolean = pending && desiredRunning

    @Synchronized
    fun wantsRunning(): Boolean = desiredRunning

    @Synchronized
    fun finishLoad(onRunning: () -> Unit): Boolean {
        if (!desiredRunning || pending) return false
        onRunning()
        return true
    }
}

/** Configuration loads and live node/mode changes must never race in the native core. */
internal object CoreSwitchLock {
    val mutex = Mutex()
}
