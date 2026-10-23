# Практика 1 модуля 4 — Unit-тесты для Gateway

> **Артефакт**: №3 — Unit-тесты на JUnit 5 и Mockito

## Что сделано

- Добавлены изолированные unit-тесты для агрегации health-check результатов в [`HealthServiceTest.java`](../services/gateway/src/test/java/com/processing/gateway/health/HealthServiceTest.java). Mockito подменяет HTTP-клиент и конфигурацию downstream-сервисов; `StepVerifier` проверяет реактивный результат.
- Добавлены параметризованные тесты для валидации запросов авторизации в [`TransactionRequestValidatorTest.java`](../services/gateway/src/test/java/com/processing/gateway/validation/TransactionRequestValidatorTest.java): валидный запрос, неподдерживаемый MTI, PAN неверной длины/формата, нулевая и отрицательная суммы, неверная длина валюты и MCC.
- В существующем workflow [`ci.yml`](../.github/workflows/ci.yml) включён запуск Maven unit-тестов: до этой работы шаг собирал Java-сервисы с `-DskipTests`.
- В [`e2e-tests/pom.xml`](../services/e2e-tests/pom.xml) восстановлено предусмотренное родительским POM условие пропуска E2E по умолчанию; E2E запускаются отдельно через профиль `e2e-tests` с поднятой СМП.

В коде Gateway нет класса `GatewayService` или `SwitchClient`, указанного в старой инструкции. Поэтому тестируется фактическая реализация проекта: `HealthService` как компонент, изолирующий downstream-проверки, и `TransactionRequestValidator` как самостоятельная бизнес-валидация.

## Критерии практики

- [x] Используются JUnit 5 и Mockito.
- [x] Тесты следуют Arrange–Act–Assert; общая фикстура создаётся через `@BeforeEach`.
- [x] Зависимости `HealthService` замокированы, проверяется содержимое результата, а не только отсутствие исключения.
- [x] Параметризованные тесты проверяют несколько классов невалидных входных данных.
- [x] CI-шаг обновлён: Maven больше не запускается с `-DskipTests`.
- [x] Полный Maven reactor на JDK 21: Gateway — 18 тестов, E2E пропущены по умолчанию.
