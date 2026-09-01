from pathlib import Path

path = Path('app/src/main/java/ru/doronin/healthconnector/StreamingMainActivity.kt')
text = path.read_text(encoding='utf-8')

replacements = [
    (
        '    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }\n',
        '    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }\n'
        '    private val secureTokenStore by lazy { SecureTokenStore(this) }\n'
    ),
    (
        '        binding.token.setText(prefs.getString("token", ""))\n',
        '        binding.token.setText(secureTokenStore.getToken())\n'
    ),
    (
        '''        prefs.edit()\n            .putString("endpoint", endpoint)\n            .putString("token", token)\n            .putInt("days", days)\n            .apply()\n        BackgroundSyncScheduler.apply(this)\n        return Settings(endpoint, token, days)\n''',
        '''        prefs.edit()\n            .putString("endpoint", endpoint)\n            .putInt("days", days)\n            .remove("token")\n            .apply()\n        secureTokenStore.setToken(token)\n        BackgroundSyncScheduler.apply(this)\n        return Settings(endpoint, token, days)\n'''
    ),
    (
        '''                put("version", 3)\n                put("endpoint", settings.endpoint)\n                put("token", settings.token)\n                put("days", settings.days)\n                put("backgroundSync", prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true))\n''',
        '''                put("version", 4)\n                put("endpoint", settings.endpoint)\n                put("days", settings.days)\n                put("backgroundSync", prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true))\n                put("tokenIncluded", false)\n'''
    ),
    (
        '            binding.status.text = "Настройки сохранены в файл"\n',
        '            binding.status.text = "Настройки сохранены без API-токена"\n'
    ),
    (
        '''            val endpoint = json.optString("endpoint")\n            val token = json.optString("token")\n            val days = json.optInt("days", 7).coerceIn(1, 30)\n            val backgroundSync = json.optBoolean("backgroundSync", true)\n            require(endpoint.isNotBlank() && token.isNotBlank()) { "В файле нет URL или токена" }\n\n            binding.endpoint.setText(endpoint)\n            binding.token.setText(token)\n            binding.days.setText(days.toString())\n            prefs.edit()\n                .putString("endpoint", endpoint)\n                .putString("token", token)\n                .putInt("days", days)\n                .putBoolean(BackgroundSyncScheduler.PREF_ENABLED, backgroundSync)\n                .apply()\n''',
        '''            val endpoint = json.optString("endpoint").trim()\n            val importedToken = json.optString("token").trim()\n            val days = json.optInt("days", 7).coerceIn(1, 30)\n            val backgroundSync = json.optBoolean("backgroundSync", true)\n            require(endpoint.isNotBlank()) { "В файле нет URL Apps Script" }\n\n            binding.endpoint.setText(endpoint)\n            if (importedToken.isNotBlank()) {\n                secureTokenStore.setToken(importedToken)\n                binding.token.setText(importedToken)\n            } else {\n                binding.token.setText(secureTokenStore.getToken())\n            }\n            binding.days.setText(days.toString())\n            prefs.edit()\n                .putString("endpoint", endpoint)\n                .putInt("days", days)\n                .putBoolean(BackgroundSyncScheduler.PREF_ENABLED, backgroundSync)\n                .remove("token")\n                .apply()\n'''
    ),
    (
        '        val connection = URL(endpoint).openConnection() as HttpURLConnection\n',
        '        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)\n'
        '        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection\n'
    ),
    (
        '        if (code !in 200..299) error("HTTP $code: $response")\n',
        '        if (code !in 200..299) error("HTTP $code")\n'
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, got {count}: {old[:100]!r}')
    text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
