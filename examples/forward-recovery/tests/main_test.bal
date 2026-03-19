import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

type StartResponse record {|
    string workflowId;
|};

type RetryResponse record {|
    string status;
    string message;
|};

type WorkflowResponse record {
    string status;
    OrderResult result;
};

// ---------------------------------------------------------------------------
// FORWARD RECOVERY — payment fails, user sends corrected card, retry succeeds
// ---------------------------------------------------------------------------

@test:Config {}
function testForwardRecovery() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start an order with a card that will be declined
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-001",
        item: "standing-desk",
        amount: 799.00,
        cardToken: "tok_declined"
    });
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Wait for workflow to reach the payment-retry wait point
    runtime:sleep(5);

    // Send corrected payment details (new card token)
    RetryResponse retryResp = check cl->post(string `/orders/${startResp.workflowId}/retryPayment`, {
        cardToken: "tok_visa_4242",
        amount: ()
    });
    test:assertEquals(retryResp.status, "accepted");

    // Get workflow result (blocks until complete)
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.orderId, "ORD-TEST-001");
    test:assertTrue(result.result.message.includes("FULFILLED"), "Should contain fulfillment ID");
}

// ---------------------------------------------------------------------------
// FORWARD RECOVERY WITH AMOUNT CHANGE — user corrects both card and amount
// ---------------------------------------------------------------------------

@test:Config {}
function testForwardRecoveryWithAmountChange() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start an order with a card that will be declined
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-002",
        item: "monitor",
        amount: 499.00,
        cardToken: "tok_declined"
    });

    // Wait for workflow to reach the payment-retry wait point
    runtime:sleep(5);

    // Send corrected payment details (new card + adjusted amount)
    RetryResponse _ = check cl->post(string `/orders/${startResp.workflowId}/retryPayment`, {
        cardToken: "tok_mastercard_1234",
        amount: 450.00
    });

    // Get workflow result
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.orderId, "ORD-TEST-002");
    test:assertTrue(result.result.message.includes("450"), "Should reflect corrected amount");
}
