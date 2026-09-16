# HealthConnector

Android-приложение для сбора персональных health-данных из **Android Health Connect** и импорта пищевого дневника из **FatSecret** в Google Sheets через Google Apps Script.

Текущее состояние: **1.6.8** (`versionCode 22`).

> Важно: FatSecret-интеграция работает **не** как подключаемое приложение внутри списка интеграций FatSecret. Отчёт передаётся через Android **«Поделиться» → «FatSecret → Health Connector 2»**.

## Что реализовано

### Health Connect → Google Sheets

HealthConnector читает Health Connect и синхронизирует данные через Apps Script. В текущем manifest предусмотрено чтение:

- шагов и дистанции;
- сна и стадий сна;
- пульса и resting HR;
- HRV;
- SpO₂;
- respiratory rate;
- веса;
- тренировок;
- active / total calories;
- elevation / floors;
- speed;
- step/cycling cadence;
- power;
- VO₂ max;
- skin temperature.

Поток:

```text
Health Connect
  → HealthConnector
  → HTTPS / Google Apps Script
  → Google Sheets
```

### FatSecret → Google Sheets

Поток:

```text
FatSecret
  → экспорт пищевого отчёта
  → Android Share Sheet
  → ShareCsvActivity
  → нормализация/объединение CSV
  → HTTPS POST action=fatsecretCsv
  → Apps Script integrity check
  → Трекер_питания + Питание + HC_Журнал
```

В **1.6.5** поддержаны дополнительные слоты FatSecret:

```text
До Завтрака
После Завтрака
До Обеда
Полдник
До Ужина
После Ужина
```

`Полдник` остаётся отдельной категорией. Остальные промежуточные слоты сводятся в `Перекус/Другое`.

Если в один день заполнено несколько таких слотов и/или обычный `Перекус/Другое`, приложение:

- суммирует их КБЖУ;
- объединяет строки продуктов;
- не допускает перезаписи одного блока другим;
- отправляет на сервер уже нормализованный CSV.

Подробно: [docs/FATSECRET_IMPORT.md](docs/FATSECRET_IMPORT.md).

## Почему импорт FatSecret устроен именно так

FatSecret может отдавать отчёт Android с разными MIME-типами. Поэтому `ShareCsvActivity` принимает `ACTION_SEND` / `ACTION_SEND_MULTIPLE` с `*/*`, а содержимое валидируется уже внутри приложения/на сервере.

Сервер после парсинга обязательно сравнивает сумму КБЖУ по приёмам пищи с суточным итогом FatSecret:

```text
KCAL_TOLERANCE = 2 kcal
MACRO_TOLERANCE = 0.2 g
```

Если суммы расходятся, импорт отклоняется и подробная причина записывается в `HC_Журнал`. Это специально сделано так, чтобы незаметно не потерять еду при изменении формата FatSecret.

## История последних FatSecret-исправлений

### 1.6.2

Расширен приём Android Share payload: URI, text, ClipData и варианты MIME.

### 1.6.3

Share receiver переведён на wildcard `*/*`, потому что на отдельных Android/FatSecret отчёт не попадал в список доступных приложений при узком MIME-filter.

### 1.6.4

Добавлена нормализация вариантов названия `Перекус/Другое` / `Перекусы/Другое` и английских аналогов.

### 1.6.5

Исправлена основная проблема с **дополнительными meal slots FatSecret**. Теперь промежуточные слоты объединяются с `Перекус/Другое` с корректным суммированием КБЖУ и объединением продуктов.

Причиной доработки был реальный integrity error за 01.09.2026: суточный итог FatSecret был 2568 kcal, а распознанные meal blocks давали только 2434 kcal. Дополнительные слоты ранее не входили в набор известных категорий.

### 1.6.8

- Share и ручной импорт используют один `FatSecretCsvNormalizer`; добавлены unit-тесты meal-slot mapping/merge и CSV quoting.
- Текст продуктов из внешнего CSV экранируется перед записью в Google Sheets от formula injection (`=`, `+`, `-`, `@`).
- Стабильный APK переведён с debug на подписанный release build (`debuggable=false`, R8).
- Удалён `ACTION_VIEW/BROWSABLE` вход для произвольных файлов; остаётся пользовательский Android Share flow.
- Удалены мёртвые Apps Script diary-функции и дубли `isPermissionFailure()`.
- Workout summaries переиспользуют дневные Health Connect records вместо повторных запросов на каждую тренировку.

## Архитектура проекта

```text
app/
  Android application

app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt
  Приём FatSecret Share Intent и POST.

app/src/main/java/ru/doronin/healthconnector/FatSecretCsvNormalizer.kt
  Общий CSV parser и meal-slot normalization/merge для Share и ручного импорта.

app/src/main/AndroidManifest.xml
  Health Connect READ permissions и Share intent filters.

apps-script/Code.gs
  Google Apps Script endpoint, Health Connect import, FatSecret parser,
  integrity validation, запись в Google Sheets.

.github/workflows/android.yml
  CI, security checks, stable APK signing и artifact.

docs/FATSECRET_IMPORT.md
  Подробная документация FatSecret + troubleshooting + release runbook.
```

## Google Sheets

Ключевые листы серверной части:

```text
HC_Дни
HC_Тренировки
HC_Сон
HC_Измерения
HC_Журнал
Трекер_питания
Питание
```

Для диагностики FatSecret **всегда сначала смотреть `HC_Журнал`**.

## Конфигурация и безопасность

Приложение использует:

- HTTPS-only endpoint;
- `android:usesCleartextTraffic="false"`;
- API token в `SecureTokenStore`;
- отсутствие API token в исходниках/SharedPreferences plain text;
- стабильную подпись APK для установки поверх предыдущей версии.

Application ID:

```text
ru.doronin.healthconnector.stable
```

SHA-256 стабильного development certificate:

```text
319c0e1944727ba1128e6a8340bd68123b9e8b2cf328b0443e1e4a0387a85a80
```

**Не менять applicationId или сертификат**, если новая APK должна ставиться поверх существующей без удаления приложения.

## Сборка APK

Основной workflow:

```text
.github/workflows/android.yml
```

Запускается при push в `main`, PR и вручную через `workflow_dispatch`.

Для устанавливаемого stable artifact должны быть настроены Actions Secrets:

```text
HC_SIGNING_KEY_B64
HC_SIGNING_STORE_PASSWORD
HC_SIGNING_KEY_ALIAS
HC_SIGNING_KEY_PASSWORD
```

При успешной сборке artifact:

```text
health-connector-apk
```

CI запускает unit-тесты и собирает настоящий `release` (`debuggable=false`, R8 включён). Если signing secret отсутствует, release compile/security check проходит, но stable APK не публикуется.

## Быстрая диагностика FatSecret

Если HealthConnector **не виден в меню «Поделиться»** — проверить manifest и wildcard `ACTION_SEND */*`.

Если приложение **видно, но импорт падает** — открыть последнюю FatSecret-ошибку в `HC_Журнал`.

Если ошибка содержит:

```text
kcal meals=..., daily=...
```

не увеличивать tolerance. Нужно искать новый/неизвестный meal slot или изменение структуры CSV.

Если APK **не ставится поверх текущей** — проверить applicationId и fingerprint подписи.

Полный алгоритм: [docs/FATSECRET_IMPORT.md](docs/FATSECRET_IMPORT.md#6-диагностика).

## Следующие действия

Приоритетные задачи:

1. Расширить regression fixtures реальным обезличенным проблемным CSV и английскими вариантами отчёта.
2. Добавить локальную integrity-проверку CSV в Android до отправки на сервер.
3. При необходимости сохранять исходный `SourceMealSlot`, даже если каноническая категория остаётся `Перекус/Другое`.
4. Автоматизировать version tags / GitHub Releases и прикладывать проверенный APK к release, а не искать его среди Actions artifacts.
5. Решить явно, поддерживаем ли несколько файлов в `ACTION_SEND_MULTIPLE` или только один отчёт за импорт.

## Правило для будущих изменений

Если FatSecret снова меняет экспорт:

1. не ослаблять server integrity check;
2. получить фактический CSV;
3. посмотреть `HC_Журнал`;
4. воспроизвести проблему на fixture;
5. исправить parser/mapping;
6. добавить regression test;
7. увеличить `versionCode` / `versionName`;
8. собрать APK через основной workflow;
9. установить поверх предыдущей версии и проверить реальным экспортом;
10. обновить документацию, если изменился контракт импорта.
