package ru.doronin.healthconnector

import java.net.URI

object EndpointSecurity {
    fun requireHttps(endpoint: String): String {
        val clean = endpoint.trim()
        val uri = runCatching { URI(clean) }
            .getOrElse { throw IllegalArgumentException("Некорректный URL Apps Script") }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для Apps Script разрешён только HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "В URL Apps Script отсутствует домен" }
        return clean
    }
}
