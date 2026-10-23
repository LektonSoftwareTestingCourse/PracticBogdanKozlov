# А4. Sequence — три сценария транзакции

![Диаграмма А4: последовательность транзакции](04-sequence.png)

**Требование практики:** показать happy path, отказ карты BLOCKED и reversal/rollback после проблем с публикацией журнала; отдельно различить синхронный запрос и асинхронное логирование. В исходном описании был только happy path.

## Чего не хватало и что сделано

- **Не хватало:** сценариев DECLINED и reversal, а также временной границы между ответом клиенту и фактической записью транзакции в Logger.
- **Сделано:** описаны happy path, отказ BLOCKED и компенсационный rollback; sync-вызовы отделены от AMQP-публикации и последующего сохранения.

## 1. Happy path

Клиент отправляет 0100 в Gateway. Switch нормализует идентификаторы и выбирает issuerId локально по BIN. Authorization получает карту, делает best-effort HTTP lookup BIN, проверяет статус, срок, баланс и лимиты, затем запрашивает резервирование. Card Management уменьшает доступный баланс и сохраняет резерв. После ответа APPROVED Switch публикует TransactionRequest в RabbitMQ; Logger потребляет сообщение и сохраняет его в PostgreSQL позднее, то есть уже после возможного ответа клиенту.

## 2. DECLINED — BLOCKED

Authorization получает CardModel со статусом BLOCKED и возвращает соответствующий `DeclineOutcome`. В этой ветке резерв и обновление лимитов не выполняются после проверки статуса. Switch строит результат транзакции и отправляет его на асинхронное логирование.

## 3. Rollback после ошибки публикации журнала

Если после APPROVED попытка публикации вернула `false`, RouteService вызывает `AuthorizationClient.rollback`, передаёт RRN/PAN/amount через внутренний HTTP endpoint, а Card Management восстанавливает баланс и помечает резерв `ROLLED_BACK`. Клиенту возвращается системная ошибка с кодом 96 по описанному контракту.

## Сверка с кодом

- [Условие практики](../../../../Lectures/materials/module-03-pyramid/practice/practice-03.md) и [инструктаж](../../../../Lectures/materials/module-03-pyramid/practice/practice-03-guide.md) задают три сценария и смысл async-вилки.
- Весь синхронный оркестрирующий путь: [RouteService.route](../../../services/switch/src/main/java/com/processing/service/RouteService.java#L55), [AuthorizationClient](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java#L68), [AuthServiceImpl.authorize](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L43), [CardServiceImpl.reserve/rollback](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java#L197).
- Отказ BLOCKED: [AuthServiceImpl status checks](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L72), [DeclineOutcome.CARD_BLOCKED](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java#L73).
- Асинхронная публикация/потребление: [LoggerClient](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java#L38), [TransactionLogListener](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java#L27).

| Класс / метод | Что делает в сценарии |
|---|---|
| [RouteService.route](../../../services/switch/src/main/java/com/processing/service/RouteService.java#L55) | Нормализует запрос, маршрутизирует по BIN, вызывает authorize, формирует TransactionRequest и запускает публикацию; при `log=false` после APPROVED инициирует rollback. |
| [AuthServiceImpl.authorize](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L43) | Проверяет статус/срок/средства/лимиты, резервирует сумму либо формирует отказ. BLOCKED завершается до limit update и reserve. |
| [CardServiceImpl.reserve](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java#L197) | Уменьшает доступный остаток и сохраняет Reservation. |
| [LoggerClient.log](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java#L38) | Публикует запись транзакции в RabbitMQ; `false` возвращается при пойманном `AmqpException`. |
| [AuthorizationClient.rollback](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java#L99) и [AuthServiceImpl.rollback](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L223) | Формируют и обрабатывают внутренний rollback-запрос с RRN, PAN и amount. |
| [CardServiceImpl.rollback](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java#L212) | Обновляет резерв и возвращает сумму в доступный баланс карты. |
| [TransactionLogListener.handleTransaction](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java#L27) | Асинхронно принимает сообщение и передаёт его в `TransactionService.store`. |
| [AuthorizationRequest.forReversal](../../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java#L83) | Создаёт запрос с MTI `0400`; компенсационный путь RouteService использует отдельный `RollbackRequest`, а не этот метод. |
