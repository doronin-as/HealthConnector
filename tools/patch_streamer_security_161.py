from pathlib import Path

path = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
text = path.read_text(encoding='utf-8')

old = '        val connection = URL(endpoint).openConnection() as HttpURLConnection\n'
new = '        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)\n        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection\n'
if text.count(old) != 1:
    raise SystemExit(f'expected one direct endpoint connection, got {text.count(old)}')
text = text.replace(old, new, 1)

old = '        if (code !in 200..299) error("HTTP $code: $response")\n'
new = '        if (code !in 200..299) error("HTTP $code")\n'
if text.count(old) != 1:
    raise SystemExit(f'expected one response-body HTTP error, got {text.count(old)}')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
