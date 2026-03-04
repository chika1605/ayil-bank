# Ayil Bank - REST-сервис для внутренних переводов

## Описание
REST-сервис для внутреннего перевода денежных средств между счетами внутри банка.

## Технологии
- Java 17
- Spring Boot 4.0.3
- PostgreSQL
- Lombok
- JPA/Hibernate

## Архитектура
Проект следует слоистой архитектуре:
- **Controller** - REST endpoints
- **Service** - бизнес-логика
- **Repository** - работа с БД
- **Entity** - модели данных
- **DTO** - объекты передачи данных
- **Exception** - обработка исключений

## API Endpoint

### POST /api/transfers
Выполняет перевод между счетами.

**Headers:**
- `Idempotency-Key` (required) - UUID ключ для идемпотентности

**Request Body:**
```json
{
  "fromAccountNumber": "ACC001",
  "toAccountNumber": "ACC002",
  "amount": 100.00
}
```

**Response (201 Created):**
```json
{
  "transactionId": 1,
  "fromAccountNumber": "ACC001",
  "toAccountNumber": "ACC002",
  "amount": 100.00,
  "status": "SUCCESS",
  "message": "Transfer completed successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

## Бизнес-правила
- Оба счета должны существовать
- Оба счета должны иметь статус ACTIVE
- Баланс отправителя должен быть достаточным
- Сумма перевода должна быть больше нуля
- Нельзя переводить средства самому себе
- Сохраняется запись о транзакции со статусом (SUCCESS/FAILED)

## Предотвращение Race Condition

### 1. Pessimistic Locking (Пессимистичная блокировка)
В `AccountRepository` используется `@Lock(LockModeType.PESSIMISTIC_WRITE)`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
Optional<Account> findByAccountNumberWithLock(String accountNumber);
```
Это блокирует строки в БД на время транзакции, предотвращая одновременное изменение одного счета несколькими потоками.

### 2. @Transactional
Все операции выполняются в одной транзакции:
```java
@Transactional
public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
    // вся логика перевода
}
```
Гарантирует атомарность операций - либо все изменения применяются, либо откатываются.

### 3. Optimistic Locking (Оптимистичная блокировка)
В Entity `Account` используется `@Version`:
```java
@Version
private Long version;
```
Дополнительная защита от конфликтов при одновременном обновлении.

### 4. Idempotency-Key
Предотвращает дублирование транзакций при повторных запросах:
```java
if (idempotencyKey != null) {
    var existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey);
    if (existingTransaction.isPresent()) {
        return buildResponseFromTransaction(existingTransaction.get());
    }
}
```

### Как это работает вместе:
1. При запросе перевода сервис получает пессимистичную блокировку на оба счета
2. Другие потоки, пытающиеся получить доступ к этим счетам, будут ждать
3. Все операции выполняются в одной транзакции
4. При успехе - изменения фиксируются, при ошибке - откатываются
5. Блокировки освобождаются после завершения транзакции

## Настройка БД

### PostgreSQL
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ayil_bank
    username: postgres
    password: postgres
```

Создайте БД:
```sql
CREATE DATABASE ayil_bank;
```

### Тестовые данные
После запуска приложения добавьте тестовые счета:
```sql
INSERT INTO accounts (account_number, balance, status, version) VALUES
('ACC001', 1000.00, 'ACTIVE', 0),
('ACC002', 500.00, 'ACTIVE', 0),
('ACC003', 2000.00, 'INACTIVE', 0),
('ACC004', 1500.00, 'BLOCKED', 0);
```

Или через Docker:
```bash
docker exec -it ayil_bank_db psql -U postgres -d ayil_bank -c "INSERT INTO accounts (account_number, balance, status, version) VALUES ('ACC001', 1000.00, 'ACTIVE', 0), ('ACC002', 500.00, 'ACTIVE', 0), ('ACC003', 2000.00, 'INACTIVE', 0), ('ACC004', 1500.00, 'BLOCKED', 0) ON CONFLICT DO NOTHING;"
```

## Запуск

### Локально
```bash
mvn spring-boot:run
```

### Docker Compose
```bash
docker-compose up -d
```

Приложение будет доступно на http://localhost:8080

### Остановка
```bash
docker-compose down
```

## Тестирование

### 1. Успешный перевод
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440001" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 201 Created, перевод выполнен успешно

### 2. Идемпотентность (повторный запрос с тем же ключом)
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440001" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 201 Created, тот же transactionId, баланс не изменился

### 3. Недостаточный баланс
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440002" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002",
    "amount": 10000.00
  }'
```
**Ожидается:** 400 Bad Request, "Недостаточно средств на счете отправителя"

### 4. Неактивный счет
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440003" \
  -d '{
    "fromAccountNumber": "ACC003",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 400 Bad Request, "Счет отправителя не активен"

### 5. Заблокированный счет
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440004" \
  -d '{
    "fromAccountNumber": "ACC004",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 400 Bad Request, "Счет отправителя не активен"

### 6. Перевод самому себе
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440005" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC001",
    "amount": 50.00
  }'
```
**Ожидается:** 400 Bad Request, "Нельзя переводить средства самому себе"

### 7. Счет не найден
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440006" \
  -d '{
    "fromAccountNumber": "ACC999",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 404 Not Found, "Счет отправителя не найден: ACC999"

### 8. Валидация (пустое поле)
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440007" \
  -d '{
    "fromAccountNumber": "",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 400 Bad Request, "Номер счета отправителя обязателен"

### 9. Валидация (отрицательная сумма)
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440008" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002",
    "amount": -50.00
  }'
```
**Ожидается:** 400 Bad Request, "Сумма должна быть больше нуля"

### 10. Отсутствие Idempotency-Key
```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002",
    "amount": 100.00
  }'
```
**Ожидается:** 400 Bad Request, "Required header 'Idempotency-Key' is not present"

## Unit-тесты
Запуск тестов:
```bash
mvn test
```

Покрытие:
- Успешный перевод
- Счет не найден
- Недостаточный баланс
- Неактивный счет
- Перевод на тот же счет
- Идемпотентность

## Обработка ошибок
- `404 NOT_FOUND` - счет не найден
- `400 BAD_REQUEST` - недостаточный баланс, неактивный счет, невалидные данные
- `500 INTERNAL_SERVER_ERROR` - внутренняя ошибка сервера

## Логирование
Настроено логирование на уровне INFO для бизнес-логики и DEBUG для транзакций.
