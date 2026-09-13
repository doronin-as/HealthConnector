# Автоматический deploy Google Apps Script

HealthConnector может автоматически публиковать `apps-script/Code.gs` после merge/push в `main`.

Workflow: `.github/workflows/apps-script-deploy.yml`.

Он запускается:

- автоматически при изменениях `apps-script/**` в `main`;
- вручную через **Actions → Deploy Apps Script → Run workflow**.

## Что делает workflow

1. Авторизуется в Google через `clasp`.
2. Скачивает текущий Apps Script проект (`clasp pull`), чтобы сохранить существующий `appsscript.json` и любые дополнительные серверные файлы.
3. Заменяет только серверный файл `Code` содержимым `apps-script/Code.gs` из GitHub.
4. Выполняет `clasp push --force`.
5. Создаёт новую неизменяемую версию Apps Script.
6. Обновляет **существующий** deployment Web App на новую версию.

Deployment ID не меняется, поэтому URL Web App в Android-приложении остаётся прежним.

## Одноразовая настройка

### 1. Включить Apps Script API

На Google-аккаунте владельца Apps Script проекта включить Apps Script API в пользовательских настройках Apps Script.

### 2. Авторизовать clasp локально

Нужен Node.js 20+.

```bash
npm install -g @google/clasp@3.4.1
clasp login
```

После входа `clasp` создаст файл авторизации:

```text
~/.clasprc.json
```

На Windows PowerShell его содержимое удобно скопировать так:

```powershell
Get-Content "$HOME\.clasprc.json" -Raw | Set-Clipboard
```

На Linux/macOS:

```bash
cat ~/.clasprc.json
```

Этот JSON является секретом: не добавлять его в Git и не отправлять в issue/PR/logs.

### 3. Найти Script ID

В Apps Script открыть **Project Settings → Script ID**.

Сохранить значение в GitHub Actions Secret:

```text
APPS_SCRIPT_ID
```

### 4. Найти Deployment ID

В Apps Script открыть **Deploy → Manage deployments**, выбрать текущий Web App и скопировать **Deployment ID**.

Сохранить значение в GitHub Actions Secret:

```text
APPS_SCRIPT_DEPLOYMENT_ID
```

Важно: нужен именно ID существующего рабочего deployment. Workflow обновляет его, а не создаёт новый URL.

### 5. Добавить OAuth JSON

В GitHub открыть:

**Repository → Settings → Secrets and variables → Actions → New repository secret**

Создать secret:

```text
CLASPRC_JSON
```

и вставить в него полное содержимое `~/.clasprc.json` после `clasp login`.

Итого должны существовать три секрета:

```text
APPS_SCRIPT_ID
APPS_SCRIPT_DEPLOYMENT_ID
CLASPRC_JSON
```

## Первая проверка

После добавления секретов запустить вручную:

**Actions → Deploy Apps Script → Run workflow**

Успешный job заканчивается шагом `Update existing web app deployment` и показывает номер созданной Apps Script версии в Summary.

После этого обычный процесс становится таким:

```text
изменение apps-script/Code.gs
→ PR
→ merge в main
→ GitHub Actions
→ clasp pull
→ clasp push
→ create-version
→ update-deployment
→ тот же Web App URL, новый серверный код
```

## Безопасность

- `CLASPRC_JSON` содержит OAuth refresh token и хранится только в GitHub Actions Secrets.
- `.clasprc.json` нельзя коммитить.
- Workflow не печатает содержимое credentials в лог.
- Используется фиксированная версия `@google/clasp@3.4.1`, чтобы обновление CLI само по себе не сломало production deploy.
- Перед `push` workflow сначала делает `pull`: текущий manifest и дополнительные Apps Script файлы не удаляются из-за того, что в репозитории хранится только `Code.gs`.
- Deploy jobs сериализованы через `concurrency`, поэтому два server deploy не выполняются одновременно.

Если OAuth-доступ когда-либо утечёт, нужно отозвать доступ clasp в Google Account, выполнить `clasp login` заново и заменить `CLASPRC_JSON` в GitHub Secrets.
