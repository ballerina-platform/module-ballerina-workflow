# Order Processing with Payment Confirmation

Demonstrates order workflow waiting for payment confirmation via external data.

## Features

- Future-based payment waiting using `future<PaymentEvent>`
- Workflow ID based routing for payment data
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

## Data Flow

```
Order Request → Check Inventory → Wait for Payment Data → Complete Order
                                       ↑
                                  Payment Data
```

Key: Uses future-based external data with `record {| future<T>...; |}` parameter.
