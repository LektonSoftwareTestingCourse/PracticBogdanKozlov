# Диаграммы архитектуры СМП (практика 3)

PlantUML-исходники, редактируемые draw.io-версии и пояснения со ссылками на код для каждой из шести схем задания. Все схемы ограничены рендером в
1600×900 (16:9). Контейнерная схема перестроена в компактные группы
клиентов, обработки и инфраструктуры, чтобы не уходить за край экрана.
Чтобы отрендерить, откройте каждый `.puml` в PlantUML-совместимом редакторе.

| Диаграмма | Файлы | Содержание / уточнение |
|---|---|---|
| А1 Context | `01-context.puml`, `01-context.drawio`, `01-context.md` | граница СМП, акторы, внешний BIN Lookup и протоколы |
| А2 Containers | `02-containers.puml`, `02-containers.drawio`, `02-containers.md` | сервисы, БД, RabbitMQ; sync HTTP/JDBC — сплошные стрелки, async AMQP — пунктиром; компактная компоновка |
| А3 Components | `03-components.puml`, `03-components.drawio`, `03-components.md` | Authorization и Card Management: контроллеры, сервисы, HTTP-клиенты, репозитории, outbox |
| А4 Sequence | `04-transaction-sequence.puml`, `04-transaction-sequence.drawio`, `04-transaction-sequence.md` | happy path, отказ BLOCKED, rollback после ошибки публикации; sync-запросы и async-журнал |
| А5 RabbitMQ | `05-rabbitmq-topology.puml`, `05-rabbitmq-topology.drawio`, `05-rabbitmq-topology.md` | exchanges, queues, routing keys, consumers, DLX/DLQ; consumer retry и outbox retry раздельно |
| А6 State | `06-state-transitions.puml`, `06-state-transitions.drawio`, `06-state-transitions.md` | Card.status, Reservation.status и итог обработки; ROLLED_BACK — компенсация, не enum Logger |

## Сверка по реализации

Основные источники: `services/switch/.../RouteService.java`,
`services/switch/.../RoutingService.java`, `services/switch/.../LoggerClient.java`,
`services/authorization/.../AuthServiceImpl.java`,
`services/card-management/.../CardServiceImpl.java`,
`services/card-management/.../OutboxEventProcessorImpl.java`,
RabbitMQ-конфиги Switch, Transaction Logger, Card Management и Notification,
а также `docker-compose.yaml`.

Есть расхождения между учебным описанием и реализацией, отмеченные прямо на
диаграммах: Switch выбирает issuerId из локальной BIN-таблицы, Authorization
отдельно вызывает BIN Lookup best-effort; кроме того, хотя Switch настроен на
CORRELATED publisher confirms, `LoggerClient` явно не ожидает `CorrelationData`
перед возвратом. Из-за этого сценарий timeout → rollback из задания следует
сверить с текущим кодом перед защитой. Consumer retry и outbox retry также
разделены, поскольку это разные механизмы.
