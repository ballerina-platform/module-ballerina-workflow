import ballerina/test;

@test:Config
function testModel() returns error? {

    Activities activities = check mockProvider.getWorkflowActivities("testWorkflow");
    test:assertEquals(activities.length(), 2, "Workflow model activities length mismatch");

    WorkflowMethods methods = check mockProvider.getWorkflowMethods("testWorkflow");
    test:assertEquals(methods.queries.length(), 3, "Workflow model queries length mismatch");
    test:assertEquals(methods.signals.length(), 2, "Workflow model signals length mismatch");
}
