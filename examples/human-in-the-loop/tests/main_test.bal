import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

type StartResponse record {|
    string workflowId;
|};

// Matches management:HumanTaskGroup for HTTP response deserialization
type HumanTaskGroup record {|
    string taskName;
    string[] taskIds;
|};

type WorkflowResponse record {
    string status;
    OrderResult result;
};

// ---------------------------------------------------------------------------
// HIGH-VALUE ORDER — requires approval, manager approves
// ---------------------------------------------------------------------------

@test:Config {}
function testApprovedOrder() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start a high-value order (above APPROVAL_THRESHOLD)
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-001",
        item: "standing-desk",
        amount: 799.00
    });
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Allow the humantask child workflow to start
    runtime:sleep(3);

    // List pending tasks and verify one task type with one instance is waiting
    HumanTaskGroup[] groups = check cl->get(string `/orders/${startResp.workflowId}/tasks`);
    test:assertEquals(groups.length(), 1, "Should have one pending task type");
    test:assertEquals(groups[0].taskIds.length(), 1, "Should have one pending task instance");

    // Manager approves
    record {|string status;|} _ = check cl->post(string `/tasks/${groups[0].taskIds[0]}/complete`, {
        approved: true,
        reason: "Approved for Q2 budget"
    });

    // Workflow completes — fulfilled
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.orderId, "ORD-TEST-001");
    test:assertTrue(result.result.message.includes("FULFILLED"), "Should contain fulfillment ID");
}

// ---------------------------------------------------------------------------
// HIGH-VALUE ORDER — requires approval, manager rejects
// ---------------------------------------------------------------------------

@test:Config {}
function testRejectedOrder() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start a high-value order
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-002",
        item: "server-rack",
        amount: 2500.00
    });

    // Allow the humantask child workflow to start
    runtime:sleep(3);

    // List pending tasks
    HumanTaskGroup[] groups = check cl->get(string `/orders/${startResp.workflowId}/tasks`);
    test:assertEquals(groups.length(), 1, "Should have one pending task type");
    test:assertEquals(groups[0].taskIds.length(), 1, "Should have one pending task instance");

    // Manager rejects
    record {|string status;|} _ = check cl->post(string `/tasks/${groups[0].taskIds[0]}/complete`, {
        approved: false,
        reason: "Budget exceeded"
    });

    // Workflow completes — rejected
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertEquals(result.result.orderId, "ORD-TEST-002");
    test:assertTrue(result.result.message.includes("Budget exceeded"), "Should contain rejection reason");
}

// ---------------------------------------------------------------------------
// LOW-VALUE ORDER — auto-approved, no human interaction needed
// ---------------------------------------------------------------------------

@test:Config {}
function testAutoApprovedOrder() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start a low-value order (below APPROVAL_THRESHOLD)
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-003",
        item: "mouse-pad",
        amount: 25.00
    });

    // Workflow completes without any human task
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.orderId, "ORD-TEST-003");
    test:assertTrue(result.result.message.includes("FULFILLED"), "Should contain fulfillment ID");
}
