# Лабораторная работа 4 — unit-тесты четырёх сервисов

## Выполнено

| Сервис | Проверяемое поведение | Тесты |
|---|---|---:|
| Gateway | Агрегация health-check и валидация запроса авторизации | 18 |
| Switch | Маршрутизация, нормализация идентификаторов, retry, клиенты downstream, rollback и маскирование PAN | 61 |
| Authorization | Авторизация, статусы карты, срок действия, баланс, лимиты и rollback | 11 |
| Card Management | Проверка/генерация PAN по Луну и пагинация CardService | 10 |

Тесты используют JUnit 5, Mockito, параметризованные сценарии и структуру Arrange–Act–Assert. Зависимости внешних сервисов изолированы; для HTTP-клиентов используются локальные mock-серверы. Общие фикстуры размещены в `@BeforeEach`.

В коде Gateway отсутствуют упомянутые в старой версии задания `GatewayService` и `SwitchClient`, поэтому Gateway-тесты проверяют фактические компоненты `HealthService` и `TransactionRequestValidator`.

## JaCoCo

JaCoCo подключён для Gateway, Switch, Authorization и Card Management через общий `services/pom.xml`. Команда `mvn -f services/pom.xml -P<service> verify` запускает тесты и формирует HTML/XML в `services/<service>/target/site/jacoco/`. HTML-файл каждого отчёта называется `index.html`.

Для Switch настроен quality gate на уровне всего модуля: branch coverage не ниже 70%. Последний прогон показывает 70 из 72 ветвей (97,2%); проверка JaCoCo прошла.

GitHub Actions загружает четыре отчёта отдельными artifacts: `jacoco-gateway`, `jacoco-switch`, `jacoco-authorization`, `jacoco-card-management`.

## Результаты последнего прогона

| Сервис | Тесты | Branch coverage | Line coverage |
|---|---:|---:|---:|
| Gateway | 18 | 18/166 (10,8%) | 57/615 (9,3%) |
| Switch | 61 | 70/72 (97,2%) | 253/302 (83,8%) |
| Authorization | 11 | 17/108 (15,7%) | 97/427 (22,7%) |
| Card Management | 10 | 21/219 (9,6%) | 34/929 (3,7%) |

Общие проценты считаются по production-коду всего модуля. Поэтому они ниже в Gateway, Authorization и Card Management: в рамках лабораторной добавлены тесты выбранных бизнес-компонентов, а порог 70% задан только для ветвей Switch.

Локальный общий запуск на JDK 21: `mvn -f services/pom.xml -Pgateway,switch,authorization,card-management verify`.

## Критерии

- [x] Написаны unit-тесты для Gateway, Switch, Authorization и Card Management.
- [x] Используются JUnit 5, Mockito и параметризованные сценарии.
- [x] Проверяются результаты и граничные случаи, а не только отсутствие исключений.
- [x] JaCoCo формирует отчёт по каждому из четырёх модулей.
- [x] Branch coverage Switch не ниже 70% и проверяется в CI.
