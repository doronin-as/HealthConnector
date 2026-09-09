package ru.doronin.healthconnector

internal object HealthConnectErrorUtils {
    fun isPermissionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val value = current ?: return false
            if (value is SecurityException) return true

            val message = value.message.orEmpty()
            if (
                message.contains("SecurityException", ignoreCase = true) ||
                message.contains("does not have permission", ignoreCase = true) ||
                message.contains("permission to read data", ignoreCase = true) ||
                message.contains("permission denied", ignoreCase = true)
            ) {
                return true
            }
            current = value.cause
        }
        return false
    }
}
