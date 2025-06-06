package org.operatorfoundation.signalbridge.models

sealed class ConnectionStatus
{
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()

    data class Error(val message: String, val cause: Throwable? = null) : ConnectionStatus()

    override fun toString(): String = when (this) {
        is Disconnected -> "Disconnected"
        is Connecting -> "Connecting"
        is Connected -> "Connected"
        is Error -> "Error: $message"
    }
}