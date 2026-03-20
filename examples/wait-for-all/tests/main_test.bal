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
    TransferResult result;
};

// ---------------------------------------------------------------------------
// BOTH TEAMS APPROVE
// ---------------------------------------------------------------------------

@test:Config {}
function testBothApprove() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    // Submit a transfer
    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-001",
        fromAccount: "ACC-001",
        toAccount: "ACC-002",
        amount: 50000.00
    });
    test:assertNotEquals(startResp.workflowId, "", "Workflow ID should not be empty");

    // Wait for workflow to reach the wait points
    runtime:sleep(5);

    // Operations approves
    DataResponse opsResp = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: true, reason: "Verified source of funds"}
    );
    test:assertEquals(opsResp.status, "accepted");

    // Compliance approves
    DataResponse compResp = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: true, reason: "KYC passed"}
    );
    test:assertEquals(compResp.status, "accepted");

    // Get result
    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertEquals(result.result.transferId, "TXF-ALL-001");
    test:assertTrue(result.result.message.includes("TXN-"), "Should contain transaction reference");
}

// ---------------------------------------------------------------------------
// OPERATIONS REJECTS — transfer should fail even if compliance approves
// ---------------------------------------------------------------------------

@test:Config {}
function testOperationsRejects() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-002",
        fromAccount: "ACC-003",
        toAccount: "ACC-004",
        amount: 100000.00
    });

    runtime:sleep(5);

    // Operations rejects
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: false, reason: "Insufficient balance"}
    );

    // Compliance approves (doesn't matter — ops already rejected)
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: true, reason: "KYC passed"}
    );

    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("Operations"), "Should reference Operations");
}

// ---------------------------------------------------------------------------
// COMPLIANCE REJECTS — even though operations approved
// ---------------------------------------------------------------------------

@test:Config {}
function testComplianceRejects() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-003",
        fromAccount: "ACC-005",
        toAccount: "ACC-006",
        amount: 75000.00
    });

    runtime:sleep(5);

    // Operations approves
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: true, reason: "OK"}
    );

    // Compliance rejects
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: false, reason: "Sanctions match"}
    );

    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    test:assertTrue(result.result.message.includes("Compliance"), "Should reference Compliance");
}

// ---------------------------------------------------------------------------
// COMPLIANCE ARRIVES FIRST — order shouldn't matter
// ---------------------------------------------------------------------------

@test:Config {}
function testComplianceArrivesFirst() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-004",
        fromAccount: "ACC-007",
        toAccount: "ACC-008",
        amount: 30000.00
    });

    runtime:sleep(5);

    // Compliance sends first
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: true, reason: "Pre-approved"}
    );

    // Then operations sends
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: true, reason: "Approved"}
    );

    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertTrue(result.result.message.includes("TXN-"), "Should contain transaction reference");
}

// ---------------------------------------------------------------------------
// BOTH REJECT — first rejection reason should be reflected
// ---------------------------------------------------------------------------

@test:Config {}
function testBothReject() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-005",
        fromAccount: "ACC-009",
        toAccount: "ACC-010",
        amount: 200000.00
    });

    runtime:sleep(5);

    // Operations rejects
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: false, reason: "Account frozen"}
    );

    // Compliance also rejects
    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: false, reason: "PEP match"}
    );

    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "REJECTED");
    // Operations is checked first, so its rejection message appears
    test:assertTrue(result.result.message.includes("Operations"), "Should reference Operations rejection");
}

// ---------------------------------------------------------------------------
// BOTH ARRIVE BEFORE WORKFLOW REACHES WAIT — data is buffered
// ---------------------------------------------------------------------------

@test:Config {}
function testBothArriveEarly() returns error? {
    http:Client cl = check new ("http://localhost:8090/api");

    StartResponse startResp = check cl->post("/transfers", {
        transferId: "TXF-ALL-006",
        fromAccount: "ACC-011",
        toAccount: "ACC-012",
        amount: 15000.00
    });

    // Send both approvals immediately without waiting for the workflow to
    // reach the wait point — the data should be buffered and delivered
    // when the workflow is ready.
    runtime:sleep(1);

    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/operationsApproval`,
        {approverId: "ops-lead", approved: true, reason: "Fast track"}
    );

    DataResponse _ = check cl->post(
        string `/transfers/${startResp.workflowId}/complianceApproval`,
        {approverId: "compliance-officer", approved: true, reason: "Pre-cleared"}
    );

    WorkflowResponse result = check cl->get(string `/transfers/${startResp.workflowId}`);
    test:assertEquals(result.status, "COMPLETED");
    test:assertEquals(result.result.status, "COMPLETED");
    test:assertTrue(result.result.message.includes("TXN-"), "Should contain transaction reference");
}
