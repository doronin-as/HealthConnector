from pathlib import Path

# UI: token stays masked and is excluded from autofill/backups.
layout = Path('app/src/main/res/layout/activity_main.xml')
text = layout.read_text(encoding='utf-8')
old = '''                            android:hint="Секретный токен"\n                            android:inputType="textPassword" />'''
new = '''                            android:hint="Секретный токен"\n                            android:inputType="textPassword"\n                            android:importantForAutofill="no" />'''
if text.count(old) != 1:
    raise SystemExit('token EditText block not found exactly once')
text = text.replace(old, new, 1)
old = '                            android:text="Файл настроек содержит секретный токен. Храни его в закрытой папке."'
new = '                            android:text="API-токен не экспортируется: резервная копия содержит только несекретные настройки."'
if text.count(old) != 1:
    raise SystemExit('backup warning not found exactly once')
layout.write_text(text.replace(old, new, 1), encoding='utf-8')

# Apps Script: setup never logs or returns the secret.
code = Path('apps-script/Code.gs')
text = code.read_text(encoding='utf-8')
old = '''  if (!properties.getProperty('API_TOKEN')) {\n    const token = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');\n    properties.setProperty('API_TOKEN', token);\n  }'''
new = '''  let tokenCreated = false;\n  if (!properties.getProperty('API_TOKEN')) {\n    const token = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');\n    properties.setProperty('API_TOKEN', token);\n    tokenCreated = true;\n  }'''
if text.count(old) != 1:
    raise SystemExit('API token setup block not found exactly once')
text = text.replace(old, new, 1)
old = '''  const result = {\n    spreadsheetId: properties.getProperty('SPREADSHEET_ID'),\n    apiToken: properties.getProperty('API_TOKEN')\n  };\n  console.log(JSON.stringify(result));\n  return result;'''
new = '''  const result = {\n    spreadsheetId: properties.getProperty('SPREADSHEET_ID'),\n    tokenConfigured: Boolean(properties.getProperty('API_TOKEN')),\n    tokenCreated: tokenCreated\n  };\n  console.log(JSON.stringify(result));\n  return result;'''
if text.count(old) != 1:
    raise SystemExit('setup result block not found exactly once')
code.write_text(text.replace(old, new, 1), encoding='utf-8')

# Release version.
gradle = Path('app/build.gradle.kts')
text = gradle.read_text(encoding='utf-8')
if text.count('versionCode = 14') != 1 or text.count('versionName = "1.6.0"') != 1:
    raise SystemExit('unexpected version block')
text = text.replace('versionCode = 14', 'versionCode = 15', 1)
text = text.replace('versionName = "1.6.0"', 'versionName = "1.6.1"', 1)
gradle.write_text(text, encoding='utf-8')
