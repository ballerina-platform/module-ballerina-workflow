import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

type StartResponse record {|
    string workflowId;
|};

type ApproveResponse record {|
    string status;
    string message;
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

    // Start a high-value order (above threshold)
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-001",
        item: "standing-desk",
        amount: 799.00
    });
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Wait for workflow to reach the approval wait point
    runtime:sleep(5);

    // Approve the order
    ApproveResponse approveResp = check cl->post(string `/orders/${startResp.workflowId}/approve`, {
        approverId: "manager-1",
        approved: true,
        reason: "Approved for Q2 budget"
    });
    test:assertEquals(approveResp.status, "accepted");

    // Get workflow result
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

    // Wait for workflow to reach the approval wait point
    runtime:sleep(5);

    // Reject the order
    ApproveResponse _ = check cl->post(string `/orders/${startResp.workflowId}/approve`, {
        approverId: "manager-2",
        approved: false,
        reason: "Budget exceeded"
    });

    // Get workflow result
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertEquals(result.result.orderId, "ORD-TEST-002");
    test:assertTrue(result.result.message.includes("manager-2"), "Should contain approver ID");
}

// ---------------------------------------------------------------------------
// LOW-VALUE ORDER — auto-approved, no human interaction needed
// ---------------------------------------------------------------------------

@test:Config {}
function testAutoApprovedOrder() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Start a low-value order (below threshold)
    StartResponse startResp = check cl->post("/orders", {
        orderId: "ORD-TEST-003",
        item: "mouse-pad",
        amount: 25.00
    });

    // Get workflow result — should complete without approval
    WorkflowResponse result = check cl->get(string `/orders/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.orderId, "ORD-TEST-003");
    test:assertTrue(result.result.message.includes("FULFILLED"), "Should contain fulfillment ID");
}
