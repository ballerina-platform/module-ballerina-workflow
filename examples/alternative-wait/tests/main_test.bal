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
// MANAGER APPROVES
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

    // Manager approves via dedicated channel
    DataResponse approveResp = check cl->post(
        string `/purchases/${startResp.workflowId}/managerApproval`,
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
// DIRECTOR APPROVES
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

    // Director approves via dedicated channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/directorApproval`,
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

    // Manager rejects via dedicated channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/managerApproval`,
        {approverId: "manager-2", approved: false, reason: "Over budget"}
    );

    // Get result
    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("manager-2"), "Should contain rejector ID");
}

// ---------------------------------------------------------------------------
// DIRECTOR REJECTS
// ---------------------------------------------------------------------------

@test:Config {}
function testDirectorRejects() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-004",
        item: "diamond-keyboard",
        amount: 8000.00,
        requestedBy: "diana"
    });

    runtime:sleep(5);

    // Director rejects via dedicated channel
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/directorApproval`,
        {approverId: "director-2", approved: false, reason: "Not justified"}
    );

    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("director-2"), "Should contain rejector ID");
}

// ---------------------------------------------------------------------------
// MANAGER APPROVES FIRST — director also responds, but is ignored
// ---------------------------------------------------------------------------

@test:Config {}
function testManagerApprovesFirstDirectorIgnored() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/purchases", {
        requestId: "REQ-ALT-005",
        item: "mechanical-keyboard",
        amount: 300.00,
        requestedBy: "eve"
    });

    runtime:sleep(5);

    // Manager approves first
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/managerApproval`,
        {approverId: "manager-3", approved: true, reason: "Reasonable expense"}
    );

    // Director also responds (too late — workflow already moved on)
    DataResponse _ = check cl->post(
        string `/purchases/${startResp.workflowId}/directorApproval`,
        {approverId: "director-3", approved: false, reason: "I disagree"}
    );

    WorkflowResponse result = check cl->get(string `/purchases/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    // The manager's approval wins because it arrived first
    test:assertEquals(result.result.status, "APPROVED");
    test:assertTrue(result.result.message.includes("manager-3"), "Should reflect the first responder");
}
