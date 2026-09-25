# Чек-листы

## 1. Дымовое тестирование

### Инфраструктура

- [ ] `docker compose up -d` поднимает сервисы СМП без ошибок запуска.
- [ ] PostgreSQL доступен сервисам, которые используют БД: Card Management, Authorization, Transaction Logger, Notification Service.
- [ ] RabbitMQ доступен по AMQP и через Management UI.
- [ ] Общая Docker-сеть позволяет сервисам обращаться друг к другу по service name.
- [ ] Переменные окружения для портов, БД и RabbitMQ применяются из конфигурации.

### Gateway Service

- [ ] `GET /health` возвращает статус Gateway.
- [ ] В health-check отражается доступность downstream-сервисов: Switch, Authorization, Card Management, Logger.
- [ ] Gateway проксирует `/api/cards/**` в Card Management.
- [ ] Gateway проксирует `/api/transactions` в Switch.
- [ ] Gateway проксирует `/api/transactions/search` в Transaction Logger.
- [ ] Gateway проксирует `/api/simulator/terminal/**` в Terminal Simulator.
- [ ] Gateway проксирует `/api/simulator/merchant/**` в Merchant Simulator.
- [ ] Gateway проксирует `/api/dashboard/**` в Transaction Logger.
- [ ] Невалидный внешний запрос возвращает ошибку валидации, а не уходит в ядро.

### Card Management

- [ ] `GET /health` возвращает статус сервиса и количество карт в базе.
- [ ] `POST /api/cards/generate` создаёт заданное количество тестовых карт.
- [ ] Сгенерированные карты имеют PAN длиной 16 цифр.
- [ ] Сгенерированные карты распределяются по переданным BIN.
- [ ] `GET /api/cards/{pan}` возвращает существующую карту.
- [ ] `GET /api/cards` возвращает список карт с пагинацией.
- [ ] `PATCH /api/cards/{pan}` изменяет статус, лимиты или баланс.
- [ ] `DELETE /api/cards/{pan}` выполняет мягкое удаление карты.
- [ ] `POST /api/cards/{pan}/reserve` уменьшает доступный баланс на сумму резервирования.
- [ ] При изменении карты формируется outbox-событие для Notification Service.

### Switch / Router

- [ ] `GET /health` возвращает статус Switch и зависимость Authorization.
- [ ] `POST /api/internal/route` принимает валидный authorization request.
- [ ] Switch извлекает BIN из PAN.
- [ ] Switch определяет `issuerId` по таблице BIN.
- [ ] Switch передаёт запрос в Authorization.
- [ ] Switch возвращает в Gateway результат Authorization.
- [ ] Switch публикует транзакцию в RabbitMQ exchange `smp.transactions`.
- [ ] При неизвестном BIN транзакция отклоняется.

### Authorization

- [ ] `GET /health` возвращает статус Authorization и зависимость Card Management.
- [ ] Authorization получает карту из Card Management.
- [ ] Authorization вызывает Bin Lookup для обогащения issuerId.
- [ ] При недоступности Bin Lookup используется fallback без поломки авторизации.
- [ ] ACTIVE-карта с валидным сроком, лимитами и балансом проходит авторизацию.
- [ ] Неактивная, заблокированная или истёкшая карта отклоняется.
- [ ] При успешной авторизации вызывается резервирование в Card Management.
- [ ] В успешном ответе есть `rrn` и `authCode`.

### Terminal Simulator

- [ ] `GET /health` возвращает статус Terminal Simulator.
- [ ] `POST /api/simulator/terminal/run` принимает сценарий `normal`.
- [ ] Сценарий `normal` отправляет транзакции через Gateway.
- [ ] Сценарий `mixed` создаёт смесь обычных, крупных и decline-транзакций.
- [ ] Сценарий `high_value` создаёт транзакции для проверки лимитов и баланса.
- [ ] Сценарий `night_time` создаёт транзакции с ночным временем.
- [ ] Сценарий `declines_test` формирует ожидаемые отказы.
- [ ] Ответ симулятора содержит `totalSubmitted`, `approved`, `declined`, `elapsedMs`.

### Merchant + Acquirer Simulator

- [ ] `GET /health` возвращает статус сервиса и количество загруженных мерчантов.
- [ ] В сервисе есть база мерчантов с MCC, категорией, эквайрером и комиссией.
- [ ] `POST /api/simulator/merchant/run` принимает сценарий `grocery`.
- [ ] Сценарий `electronics` генерирует крупные покупки.
- [ ] Сценарий `restaurant` использует ресторанные MCC.
- [ ] Сценарий `travel` использует travel-MCC и крупные суммы.
- [ ] Явно переданные `mccCodes` имеют приоритет над MCC сценария.
- [ ] Сервис отправляет транзакции через Gateway.

### Transaction Logger

- [ ] `GET /health` возвращает статус Logger и количество сохранённых транзакций.
- [ ] Logger потребляет сообщения из очереди `transaction-log`.
- [ ] `POST /api/internal/log` сохраняет транзакцию как fallback-способ.
- [ ] Повторная запись транзакции с тем же `id` не создаёт дубль.
- [ ] `GET /api/transactions/search` возвращает транзакции с пагинацией.
- [ ] Поиск поддерживает фильтры по PAN, статусу, датам, merchantId, issuerId и MCC.
- [ ] `GET /api/dashboard/stats` возвращает агрегированную статистику.
- [ ] `GET /api/dashboard/recent` возвращает последние транзакции.
- [ ] WebSocket `/ws/transactions` отправляет новые транзакции подключённым клиентам.

### Bin Lookup

- [ ] Health-check Bin Lookup доступен.
- [ ] `GET /api/bin/{bin}` возвращает `issuerId`, `bankName` и `countryCode` для известного BIN.
- [ ] Для неизвестного BIN возвращается контролируемый отказ.
- [ ] Недоступность Bin Lookup не блокирует Authorization благодаря fallback.

### Notification Service

- [ ] Health-check Notification Service доступен.
- [ ] Сервис потребляет события из очереди `card-notifications`.
- [ ] События создания, обновления и удаления карты обрабатываются без падения consumer.
- [ ] Уведомления сохраняются в таблицу `card_notifications`.
- [ ] Повторная доставка события не приводит к неконтролируемому дублированию.

### Web Dashboard

- [ ] Dashboard открывается на `localhost:3000`.
- [ ] KPI-карточки загружают данные из `/api/dashboard/stats`.
- [ ] Таблица последних транзакций загружает данные из `/api/dashboard/recent`.
- [ ] Фильтры обращаются к поиску транзакций через Gateway.
- [ ] WebSocket-подключение к Logger получает новые транзакции.
- [ ] При обрыве WebSocket отображается состояние разрыва и выполняется реконнект.
- [ ] PAN в таблице маскируется.
- [ ] Детали транзакции открываются из строки таблицы.

## 2. Критический путь

### Подготовка данных

- [ ] Сгенерирован пул карт через Gateway.
- [ ] В пуле есть ACTIVE-карты с валидным `expiryDate`.
- [ ] В пуле есть карты или условия для проверок `INACTIVE`, `BLOCKED`, `EXPIRED`.
- [ ] Для выбранной ACTIVE-карты известны `dailyLimit`, `monthlyLimit` и `availableBalance`.
- [ ] Таблица BIN содержит BIN тестовых карт.

### Сквозная успешная транзакция

- [ ] Terminal Simulator получает карту из Card Management.
- [ ] Terminal Simulator формирует authorization request с `mti="0100"`.
- [ ] Gateway валидирует запрос и передаёт его в Switch.
- [ ] Switch определяет `issuerId` по BIN.
- [ ] Authorization получает карту из Card Management.
- [ ] Authorization проверяет статус карты.
- [ ] Authorization проверяет срок действия карты.
- [ ] Authorization проверяет дневной лимит.
- [ ] Authorization проверяет месячный лимит.
- [ ] Authorization проверяет доступный баланс.
- [ ] Authorization резервирует сумму через Card Management.
- [ ] Authorization возвращает `APPROVED`, `responseCode="00"`, `rrn`, `authCode`.
- [ ] Switch публикует транзакцию в RabbitMQ.
- [ ] Transaction Logger сохраняет транзакцию.
- [ ] Gateway возвращает итоговый ответ вызывающему симулятору.
- [ ] Dashboard отображает новую транзакцию в последних операциях.
- [ ] Dashboard обновляет KPI после появления транзакции.

### Сквозные отказы

- [ ] Неизвестный PAN проходит через Gateway и Switch до Authorization и завершается `DECLINED`.
- [ ] Неизвестный BIN отклоняется на уровне Switch.
- [ ] Карта `INACTIVE` отклоняется до проверки лимитов и баланса.
- [ ] Карта `BLOCKED` отклоняется до проверки лимитов и баланса.
- [ ] Карта со статусом `EXPIRED` отклоняется как истёкшая.
- [ ] ACTIVE-карта с `expiryDate` в прошлом отклоняется по сроку действия.
- [ ] Сумма выше дневного лимита отклоняется по лимиту.
- [ ] Сумма выше месячного лимита отклоняется по лимиту.
- [ ] Сумма выше доступного баланса отклоняется по недостатку средств.
- [ ] Недоступность Card Management для Authorization приводит к контролируемому отказу.
- [ ] Недоступность Authorization для Switch приводит к контролируемому отказу после retry.
- [ ] Невалидный запрос на Gateway возвращает 400 и не попадает в Switch.

### Асинхронная часть и наблюдаемость

- [ ] Для APPROVED-транзакции сообщение принято RabbitMQ.
- [ ] Для DECLINED-транзакции результат также попадает в Logger.
- [ ] Logger не создаёт дубль при повторной доставке.
- [ ] Поиск по `rrn` или PAN находит сохранённую транзакцию.
- [ ] Поиск по статусу разделяет APPROVED и DECLINED.
- [ ] Dashboard получает real-time событие по WebSocket.
- [ ] При задержке доставки через RabbitMQ результат появляется после ожидания eventual consistency.
- [ ] Outbox Card Management публикует карточное событие после изменения карты.
- [ ] Notification Service обрабатывает карточное событие.

### Симуляционные сценарии

- [ ] Terminal Simulator `normal` даёт преимущественно успешные транзакции.
- [ ] Terminal Simulator `mixed` даёт смесь approved и declined.
- [ ] Terminal Simulator `high_value` проверяет лимиты и баланс.
- [ ] Terminal Simulator `declines_test` покрывает основные decline-причины.
- [ ] Merchant Simulator `grocery` создаёт транзакции с grocery MCC.
- [ ] Merchant Simulator `electronics` создаёт крупные суммы.
- [ ] Merchant Simulator `restaurant` создаёт ресторанные MCC.
- [ ] Merchant Simulator `travel` создаёт travel MCC и крупные суммы.

### Приёмка пользовательского представления

- [ ] Dashboard показывает общее число транзакций.
- [ ] Dashboard показывает approval rate.
- [ ] Dashboard показывает общую сумму и среднее время обработки.
- [ ] Dashboard отображает Approved vs Declined.
- [ ] Dashboard таблица обновляется при новых транзакциях.
- [ ] В деталях транзакции видны STAN, RRN, PAN, сумма, статус, terminalId, merchantId, MCC, acquirerId и issuerId.
