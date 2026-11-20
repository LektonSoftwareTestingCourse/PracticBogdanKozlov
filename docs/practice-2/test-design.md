# Test-design ядра: Authorization и Card-Management

## 1. Классы эквивалентности

| Поле | Классы | Представитель | Ожидаемый результат |
|---|---|---|---|
| Authorization: `pan` | Существующая карта с валидным PAN | `4000001234560001` | Карта найдена, проверки продолжаются |
| Authorization: `pan` | PAN не найден в Card Management | `4000009999999999` | `DECLINED`, `responseCode="14"` |
| Authorization: `card_status` | `ACTIVE` | `ACTIVE` | Проверки expiry, лимитов и баланса продолжаются |
| Authorization: `card_status` | `INACTIVE` | `INACTIVE` | `DECLINED`, причина `CARD_INACTIVE` |
| Authorization: `card_status` | `BLOCKED` | `BLOCKED` | `DECLINED`, причина `CARD_BLOCKED` |
| Authorization: `card_status` | `EXPIRED` | `EXPIRED` | `DECLINED`, `responseCode="54"` |
| Authorization: `expiryDate` | Будущий месяц | следующий месяц в формате `MMYY` | Срок действия валиден |
| Authorization: `expiryDate` | Текущий месяц | текущий `MMYY` | Срок действия валиден |
| Authorization: `expiryDate` | Прошлый месяц | предыдущий `MMYY` | `DECLINED`, `responseCode="54"` |
| Authorization: сумма к дневному лимиту | `daily_amount + amount < dailyLimit` | `dailyLimit - 1` | Проверка дневного лимита пройдена |
| Authorization: сумма к дневному лимиту | `daily_amount + amount = dailyLimit` | `dailyLimit` | Проверка дневного лимита пройдена |
| Authorization: сумма к дневному лимиту | `daily_amount + amount > dailyLimit` | `dailyLimit + 1` | `DECLINED`, `responseCode="61"` |
| Authorization: сумма к месячному лимиту | `monthly_amount + amount < monthlyLimit` | `monthlyLimit - 1` | Проверка месячного лимита пройдена |
| Authorization: сумма к месячному лимиту | `monthly_amount + amount = monthlyLimit` | `monthlyLimit` | Проверка месячного лимита пройдена |
| Authorization: сумма к месячному лимиту | `monthly_amount + amount > monthlyLimit` | `monthlyLimit + 1` | `DECLINED`, `responseCode="61"` |
| Authorization: сумма к балансу | `amount < availableBalance` | `availableBalance - 1` | Проверка баланса пройдена |
| Authorization: сумма к балансу | `amount = availableBalance` | `availableBalance` | Проверка баланса пройдена |
| Authorization: сумма к балансу | `amount > availableBalance` | `availableBalance + 1` | `DECLINED`, `responseCode="51"` |
| Authorization: доступность Card Management | CMS отвечает успешно | `GET /api/cards/{pan}` -> 200 | Проверки продолжаются |
| Authorization: доступность Card Management | CMS вернул 404 | `GET /api/cards/{pan}` -> 404 | `DECLINED`, `responseCode="14"` |
| Authorization: доступность Card Management | CMS недоступен | timeout/error | `DECLINED`, `responseCode="05"`, причина `ISSUER_TIMEOUT` |
| Card Management: `bin` | 6 цифр | `400000` | Карта может быть создана |
| Card Management: `bin` | меньше 6 цифр | `40000` | Ошибка валидации |
| Card Management: `bin` | больше 6 цифр | `4000000` | Ошибка валидации |
| Card Management: `pan` | 16 цифр, Luhn валиден | `4000001234560001` | Карта может быть найдена |
| Card Management: `pan` | 16 цифр, Luhn невалиден | `4000001234560002` | Ошибка валидации или карта не используется в авторизации |
| Card Management: `pan` | длина не 16 цифр | `400000123456001` | Ошибка валидации или 404 |
| Card Management: `status` filter | Значение из списка | `ACTIVE` | Список карт фильтруется по статусу |
| Card Management: `status` filter | Значение не из списка | `DELETED_BY_USER` | Ошибка валидации фильтра |
| Card Management: `reserve.amount` | Положительная сумма | `150000` | Баланс уменьшается на сумму |
| Card Management: `reserve.amount` | Нулевая сумма | `0` | Ошибка валидации суммы |
| Card Management: `reserve.amount` | Отрицательная сумма | `-1` | Ошибка валидации суммы |

## 2. Граничные значения

| Поле | Граница | ON | OFF | Ожидаемый результат |
|---|---|---|---|---|
| `dailyLimit` | `daily_amount + amount <= dailyLimit` | `dailyLimit` | `dailyLimit - 1`, `dailyLimit + 1` | На `dailyLimit` и ниже проверка проходит; выше — `DECLINED`, `61` |
| `monthlyLimit` | `monthly_amount + amount <= monthlyLimit` | `monthlyLimit` | `monthlyLimit - 1`, `monthlyLimit + 1` | На `monthlyLimit` и ниже проверка проходит; выше — `DECLINED`, `61` |
| `availableBalance` | `amount <= availableBalance` | `availableBalance` | `availableBalance - 1`, `availableBalance + 1` | На балансе и ниже проверка проходит; выше — `DECLINED`, `51` |
| `expiryDate` | карта валидна в текущем месяце | текущий `MMYY` | предыдущий `MMYY`, следующий `MMYY` | Текущий и следующий месяц валидны; предыдущий — `DECLINED`, `54` |
| `PAN` length | 16 цифр | 16 цифр | 15 цифр, 17 цифр | 16 цифр допустимы при валидном Luhn; другая длина отклоняется |
| `BIN` length | 6 цифр | 6 цифр | 5 цифр, 7 цифр | 6 цифр допустимы; другая длина отклоняется |
| `currencyCode` length | 3 цифры | `643` | 2 цифры, 4 цифры | 3 цифры допустимы; другая длина отклоняется |
| `generate.count` | минимально осмысленное значение | `1` | `0`, `2` | `1` и выше создают карты; `0` отклоняется или не создаёт карту |
| `GET /api/cards?offset` | начало списка | `0` | `-1`, `1` | `0` и выше допустимы; отрицательное смещение отклоняется |

## 3. Попарное тестирование (pairwise)

- Модель PICT содержит 8 параметров и ограничения: `docs/practice-2/pict/model.txt`.
- Сгенерированный набор содержит 38 строк: `docs/practice-2/pict/cases.txt`.
- Каждая строка набора проецируется в отдельный тест-кейс `TC-PW-*`.
- 2-wise покрытие применено к параметрам авторизации, которые одновременно влияют на итог: статус карты, срок действия, отношение суммы к дневному/месячному лимиту и балансу, тип терминала, MCC и ответ Card Management.
- Ограничения исключают невозможные сочетания: если CMS не вернул карту или недоступен, статусы и лимиты карты не проверяются; если статус карты не `ACTIVE`, лимиты и баланс не являются ведущей причиной отказа; если `expiry=expired`, отказ определяется сроком действия.
- Критичное сочетание, добавленное вручную: `ACTIVE + future + amount_vs_daily=above + amount_vs_monthly=above + amount_vs_balance=above`. Оно проверяет порядок отказов Authorization: дневной лимит должен быть ведущей причиной раньше месячного лимита и баланса.

## 4. Тест-кейсы

| ID | Требование | Источник (класс / граница / строка набора) | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|
| TC-AUTH-001 | AUTH-02 | КЭ: существующая ACTIVE-карта | Есть карта `ACTIVE`, expiry не истёк, лимиты и баланс достаточны | Отправить `POST /api/transactions` с валидной суммой | `APPROVED`, `responseCode="00"`, есть `rrn` и `authCode` |
| TC-AUTH-002 | AUTH-02 | КЭ: PAN не найден | PAN отсутствует в Card Management | Отправить авторизацию по неизвестному PAN | `DECLINED`, `responseCode="14"` |
| TC-AUTH-003 | AUTH-02 | КЭ: status `INACTIVE` | Карта в статусе `INACTIVE` | Отправить авторизацию с валидной суммой | `DECLINED`, причина `CARD_INACTIVE` |
| TC-AUTH-004 | AUTH-02 | КЭ: status `BLOCKED` | Карта в статусе `BLOCKED` | Отправить авторизацию с валидной суммой | `DECLINED`, причина `CARD_BLOCKED` |
| TC-AUTH-005 | AUTH-02 | КЭ: status `EXPIRED` | Карта в статусе `EXPIRED` | Отправить авторизацию с валидной суммой | `DECLINED`, `responseCode="54"` |
| TC-AUTH-006 | AUTH-02 | ГЗ: expiry текущий месяц | Карта `ACTIVE`, `expiryDate` равен текущему `MMYY` | Отправить авторизацию с валидной суммой | `APPROVED`, срок действия принят как валидный |
| TC-AUTH-007 | AUTH-02 | ГЗ: expiry предыдущий месяц | Карта `ACTIVE`, `expiryDate` равен предыдущему `MMYY` | Отправить авторизацию с валидной суммой | `DECLINED`, `responseCode="54"` |
| TC-AUTH-008 | AUTH-02 | ГЗ: daily limit ON | `daily_amount + amount = dailyLimit` | Отправить авторизацию | Проверка дневного лимита проходит |
| TC-AUTH-009 | AUTH-02 | ГЗ: daily limit OFF | `daily_amount + amount = dailyLimit + 1` | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-AUTH-010 | AUTH-02 | ГЗ: monthly limit ON | `monthly_amount + amount = monthlyLimit` | Отправить авторизацию | Проверка месячного лимита проходит |
| TC-AUTH-011 | AUTH-02 | ГЗ: monthly limit OFF | `monthly_amount + amount = monthlyLimit + 1` | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-AUTH-012 | AUTH-02 | ГЗ: balance ON | `amount = availableBalance` | Отправить авторизацию | Проверка баланса проходит, при прочих валидных условиях `APPROVED` |
| TC-AUTH-013 | AUTH-02 | ГЗ: balance OFF | `amount = availableBalance + 1` | Отправить авторизацию | `DECLINED`, `responseCode="51"` |
| TC-CMS-001 | CMS-02 | КЭ: валидный BIN | Запрос создания карты содержит `bin=400000` | Отправить `POST /api/cards` | Карта создана, PAN содержит 16 цифр и валиден по Luhn |
| TC-CMS-002 | CMS-02 | ГЗ: BIN 5 цифр | Запрос содержит `bin=40000` | Отправить `POST /api/cards` | Ошибка валидации BIN |
| TC-CMS-003 | CMS-02 | ГЗ: BIN 7 цифр | Запрос содержит `bin=4000000` | Отправить `POST /api/cards` | Ошибка валидации BIN |
| TC-CMS-004 | CMS-02 | ГЗ: PAN 16 цифр | Существует карта с PAN длиной 16 | Отправить `GET /api/cards/{pan}` | Карта возвращена |
| TC-CMS-005 | CMS-02 | ГЗ: PAN 15 цифр | PAN содержит 15 цифр | Отправить `GET /api/cards/{pan}` | Ошибка валидации или 404 |
| TC-CMS-006 | CMS-05 | КЭ: reserve positive | Существует карта с достаточным балансом | Отправить `POST /api/cards/{pan}/reserve` с положительной суммой | `200 OK`, баланс уменьшен |
| TC-CMS-007 | CMS-05 | КЭ: reserve zero | Существует карта | Отправить reserve с `amount=0` | Ошибка валидации суммы |
| TC-CMS-008 | CMS-05 | КЭ: reserve negative | Существует карта | Отправить reserve с `amount=-1` | Ошибка валидации суммы |
| TC-PW-001 | AUTH-02 | Pairwise: строка 1 | `ACTIVE`, `future`, суммы ниже лимитов и баланса, `POS`, grocery, CMS ok | Отправить авторизацию | `APPROVED`, `00` |
| TC-PW-002 | AUTH-02 | Pairwise: строка 2 | `ACTIVE`, `current_month`, суммы равны лимитам и балансу, `ATM`, restaurant, CMS ok | Отправить авторизацию | `APPROVED`, `00` |
| TC-PW-003 | AUTH-02 | Pairwise: строка 3 + ручное критичное сочетание | `ACTIVE`, `future`, сумма выше дневного, месячного лимита и баланса, `ECOM`, electronics | Отправить авторизацию | `DECLINED`, ведущий отказ `responseCode="61"` по дневному лимиту |
| TC-PW-004 | AUTH-02 | Pairwise: строка 4 | CMS возвращает 404 для карты, `ATM`, travel | Отправить авторизацию | `DECLINED`, `responseCode="14"` |
| TC-PW-005 | AUTH-05 | Pairwise: строка 5 | CMS недоступен, `ECOM`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="05"`, `ISSUER_TIMEOUT` |
| TC-PW-006 | AUTH-02 | Pairwise: строка 6 | `ACTIVE`, `current_month`, daily equal, monthly above, balance above, `POS`, travel | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-007 | AUTH-02 | Pairwise: строка 7 | `ACTIVE`, `current_month`, daily above, monthly below, balance equal, `POS`, electronics | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-008 | AUTH-02 | Pairwise: строка 8 | `ACTIVE`, `future`, суммы равны лимитам и балансу, `ECOM`, grocery | Отправить авторизацию | `APPROVED`, `00` |
| TC-PW-009 | AUTH-02 | Pairwise: строка 9 | `ACTIVE`, `expired`, суммы ниже, `ATM`, electronics | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-010 | AUTH-02 | Pairwise: строка 10 | `ACTIVE`, `current_month`, monthly above, balance above, `ATM`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-011 | AUTH-02 | Pairwise: строка 11 | `ACTIVE`, `current_month`, daily above, monthly equal, balance below, `ECOM`, travel | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-012 | AUTH-02 | Pairwise: строка 12 | `INACTIVE`, `POS`, restaurant | Отправить авторизацию | `DECLINED`, `CARD_INACTIVE` |
| TC-PW-013 | AUTH-02 | Pairwise: строка 13 | `BLOCKED`, `POS`, grocery | Отправить авторизацию | `DECLINED`, `CARD_BLOCKED` |
| TC-PW-014 | AUTH-02 | Pairwise: строка 14 | status `EXPIRED`, `POS`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-015 | AUTH-02 | Pairwise: строка 15 | `ACTIVE`, `future`, daily below, monthly equal, balance equal, `POS`, electronics | Отправить авторизацию | `APPROVED`, `00` |
| TC-PW-016 | AUTH-02 | Pairwise: строка 16 | `ACTIVE`, `future`, daily above, monthly below, balance above, `ATM`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-017 | AUTH-02 | Pairwise: строка 17 | `ACTIVE`, `future`, daily equal, monthly below, balance below, `POS`, electronics | Отправить авторизацию | `APPROVED`, `00` |
| TC-PW-018 | AUTH-02 | Pairwise: строка 18 | CMS 404, `POS`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="14"` |
| TC-PW-019 | AUTH-05 | Pairwise: строка 19 | CMS timeout, `POS`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="05"` |
| TC-PW-020 | AUTH-05 | Pairwise: строка 20 | CMS timeout, `ATM`, electronics | Отправить авторизацию | `DECLINED`, `responseCode="05"` |
| TC-PW-021 | AUTH-02 | Pairwise: строка 21 | CMS 404, `ECOM`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="14"` |
| TC-PW-022 | AUTH-02 | Pairwise: строка 22 | `ACTIVE`, `future`, monthly above, `POS`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-023 | AUTH-02 | Pairwise: строка 23 | `ACTIVE`, `future`, monthly above, balance equal, `POS`, travel | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-024 | AUTH-02 | Pairwise: строка 24 | `ACTIVE`, `future`, daily above, monthly equal, balance above, `POS`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="61"` |
| TC-PW-025 | AUTH-02 | Pairwise: строка 25 | `ACTIVE`, `expired`, `POS`, grocery | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-026 | AUTH-02 | Pairwise: строка 26 | `ACTIVE`, `expired`, `ECOM`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-027 | AUTH-02 | Pairwise: строка 27 | `INACTIVE`, `ATM`, grocery | Отправить авторизацию | `DECLINED`, `CARD_INACTIVE` |
| TC-PW-028 | AUTH-02 | Pairwise: строка 28 | `INACTIVE`, `ECOM`, electronics | Отправить авторизацию | `DECLINED`, `CARD_INACTIVE` |
| TC-PW-029 | AUTH-02 | Pairwise: строка 29 | `BLOCKED`, `ATM`, restaurant | Отправить авторизацию | `DECLINED`, `CARD_BLOCKED` |
| TC-PW-030 | AUTH-02 | Pairwise: строка 30 | `BLOCKED`, `ECOM`, electronics | Отправить авторизацию | `DECLINED`, `CARD_BLOCKED` |
| TC-PW-031 | AUTH-02 | Pairwise: строка 31 | status `EXPIRED`, `ATM`, restaurant | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-032 | AUTH-02 | Pairwise: строка 32 | status `EXPIRED`, `ECOM`, electronics | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-033 | AUTH-02 | Pairwise: строка 33 | CMS 404, `POS`, electronics | Отправить авторизацию | `DECLINED`, `responseCode="14"` |
| TC-PW-034 | AUTH-05 | Pairwise: строка 34 | CMS timeout, `POS`, travel | Отправить авторизацию | `DECLINED`, `responseCode="05"` |
| TC-PW-035 | AUTH-02 | Pairwise: строка 35 | `ACTIVE`, `expired`, `POS`, travel | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
| TC-PW-036 | AUTH-02 | Pairwise: строка 36 | `INACTIVE`, `POS`, travel | Отправить авторизацию | `DECLINED`, `CARD_INACTIVE` |
| TC-PW-037 | AUTH-02 | Pairwise: строка 37 | `BLOCKED`, `POS`, travel | Отправить авторизацию | `DECLINED`, `CARD_BLOCKED` |
| TC-PW-038 | AUTH-02 | Pairwise: строка 38 | status `EXPIRED`, `POS`, travel | Отправить авторизацию | `DECLINED`, `responseCode="54"` |
