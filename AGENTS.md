# AGENTS.md — git-test-analyzer

Проект собирается Maven + Kotlin 2.1.0, JVM 17.

## Команды

- Компиляция: `mvn -q compile`
- Юнит-тесты: `mvn -q test`
- Запуск анализатора: `mvn compile exec:java`
- Сборка jar: `mvn package`

## Структура

- `src/main/kotlin/com/analyzer/` — основной код.
- `src/test/kotlin/com/analyzer/` — unit-тесты (JUnit 5).
- `src/main/resources/report-assets/chart.umd.js` — Chart.js, копируется в HTML-отчёт.
- `docs/git-test-analyzer.md` — пользовательская документация.

## Темп безопасности

- Не коммитить ничего без явной просьбы.
- Каждое изменение в Kotlin-коде должно сохранять существующие тесты зелёными.
- Новые публичные API покрываются unit-тестами.
- Кодировка файлов — UTF-8; окончания строк — LF.