# Order Processing with Payment Signal

Demonstrates order workflow waiting for payment confirmation via signals.

## Features

- Signal-based payment waiting using `ctx->awaitSignal()`
- Correlation with order ID
- Timeout handling (24 hour wait)
- Inventory checking before payment

## Running

```bash
bal run

# Place order
curl -X POST http://localhost:9094/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId": "ORD-001", "item": "laptop"}'

# Send payment (within 24 hours)
curl -X POST http://localhost:9094/orders/ORD-001/payment \
  -H "Content-Type: application/json" \
  -d '{"amount": 1500.00}'
```

## Signal Flow

```
Order Request → Check Inventory → Wait for Payment Signal → Complete Order
                                       ↑
                                  Payment Event
```

Key: Uses future-based signals with `@Signal` annotation.
