import ballerina/http;
import ballerina/lang.runtime;
import ballerina/test;

type StartResponse record {|
    string workflowId;
|};

type DataResponse record {|
    string status;
    string message;
|};

type WorkflowResponse record {
    string status;
    PurchaseResult result;
};

// ---------------------------------------------------------------------------
// MANAGER APPROVES (via shared channel)
// ---------------------------------------------------------------------------

@test:Config {}
function testManagerApproves() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Submit a purchase request
    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-001",
        item: "ergonomic-chair",
        amount: 1200.00,
        requestedBy: "alice"
    });
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Wait for workflow to reach the wait point
    runtime:sleep(5);

    // Manager approves via shared channel
    DataResponse approveResp = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "manager-1", approved: true, reason: "Within budget"}
    );
    test:assertEquals(approveResp.status, "accepted");

    // Get result
    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "APPROVED");
    test:assertEquals(result.result.requestId, "REQ-ALT-001");
    test:assertTrue(result.result.message.includes("manager-1"), "Should contain approver ID");
}

// ---------------------------------------------------------------------------
// DIRECTOR APPROVES (via shared channel)
// ---------------------------------------------------------------------------

@test:Config {}
function testDirectorApproves() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Submit a purchase request
    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-002",
        item: "standing-desk",
        amount: 800.00,
        requestedBy: "bob"
    });

    // Wait for workflow to reach the wait point
    runtime:sleep(5);

    // Director approves via shared channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "director-1", approved: true, reason: "Approved at director level"}
    );

    // Get result
    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "APPROVED");
    test:assertTrue(result.result.message.includes("director-1"), "Should contain director ID");
}

// ---------------------------------------------------------------------------
// APPROVER REJECTS
// ---------------------------------------------------------------------------

@test:Config {}
function testApproverRejects() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Submit a purchase request
    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-003",
        item: "gold-plated-monitor",
        amount: 5000.00,
        requestedBy: "charlie"
    });

    // Wait for workflow to reach the wait point
    runtime:sleep(5);

    // Approver rejects via shared channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "manager-2", approved: false, reason: "Over budget"}
    );

    // Get result
    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("manager-2"), "Should contain rejector ID");
}

// ---------------------------------------------------------------------------
// SECOND SENDER IS IGNORED (first-wins verification)
// ---------------------------------------------------------------------------

@test:Config {}
function testSecondSenderIgnored() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-004",
        item: "diamond-keyboard",
        amount: 8000.00,
        requestedBy: "diana"
    });

    runtime:sleep(5);

    // First sender rejects via shared channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "director-2", approved: false, reason: "Not justified"}
    );

    // Second sender approves — silently ignored (workflow already moved on)
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "manager-4", approved: true, reason: "I approve"}
    );

    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    // First sender's rejection wins
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("director-2"), "Should reflect the first responder");
}

// ---------------------------------------------------------------------------
// FIRST SENDER APPROVES — second sender also responds, but is ignored
// ---------------------------------------------------------------------------

@test:Config {}
function testFirstSenderWins() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-005",
        item: "mechanical-keyboard",
        amount: 300.00,
        requestedBy: "eve"
    });

    runtime:sleep(5);

    // Manager approves first via shared channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "manager-3", approved: true, reason: "Reasonable expense"}
    );

    // Director also responds to same channel (too late — workflow already moved on)
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/approval`,
        {approverId: "director-3", approved: false, reason: "I disagree"}
    );

    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    // The first response wins
    test:assertEquals(result.result.status, "APPROVED");
    test:assertTrue(result.result.message.includes("manager-3"), "Should reflect the first responder");
}

// ---------------------------------------------------------------------------
// NEW INSTANCE IS ISOLATED — a fresh workflow does not see signals
// sent to a previous workflow instance (signals are scoped by workflow ID).
// ---------------------------------------------------------------------------

@test:Config {}
function testNewInstanceDoesNotInheritPreviousSignals() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // --- Instance A: approved by manager-A ---
    StartResponse startA = check cl->post("/purchases", {
        requestId: "REQ-ALT-ISO-A",
        item: "office-chair",
        amount: 450.00,
        requestedBy: "alice"
    });
    runtime:sleep(5);

    DataResponse _ = check cl->post(
        string `/purchases/${startA.workflowId}/approval`,
        {approverId: "manager-A", approved: true, reason: "ok"}
    );

    WorkflowResponse resultA = check cl->get(string `/purchases/${startA.workflowId}`);
    test:assertEquals(resultA.status, "COMPLETED");
    test:assertEquals(resultA.result.status, "APPROVED");
    test:assertTrue(resultA.result.message.includes("manager-A"),
            "Instance A should reflect manager-A's decision");

    // --- Instance B: brand new workflow ID. Must NOT inherit manager-A's signal ---
    StartResponse startB = check cl->post("/purchases", {
        requestId: "REQ-ALT-ISO-B",
        item: "office-chair",
        amount: 450.00,
        requestedBy: "bob"
    });
    test:assertNotEquals(startB.workflowId, startA.workflowId,
            "New instance must get a distinct workflow ID");
    runtime:sleep(5);

    // Send a clearly different decision to instance B
    DataResponse _ = check cl->post(
        string `/purchases/${startB.workflowId}/approval`,
        {approverId: "director-B", approved: false, reason: "budget review"}
    );

    WorkflowResponse resultB = check cl->get(string `/purchases/${startB.workflowId}`);
    test:assertEquals(resultB.status, "COMPLETED");
    // Instance B must use its own signal, not the stale approval from instance A
    test:assertEquals(resultB.result.status, "REJECTED",
            "Instance B must reflect its own rejection, not inherit instance A's approval");
    test:assertTrue(resultB.result.message.includes("director-B"),
            "Instance B must reflect director-B's decision");
    test:assertFalse(resultB.result.message.includes("manager-A"),
            "Instance B must NOT reflect any signal from instance A");
}
