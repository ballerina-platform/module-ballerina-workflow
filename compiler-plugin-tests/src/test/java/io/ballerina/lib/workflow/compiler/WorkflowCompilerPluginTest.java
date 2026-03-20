/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.compiler;

import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.tools.diagnostics.Diagnostic;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tests for workflow compiler plugin.
 * Tests the code modifier that detects @Workflow functions and transforms @Activity calls.
 * Also tests the validator that checks @Workflow and @Activity function signatures.
 *
 * @since 0.1.0
 */
public class WorkflowCompilerPluginTest {

    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources",
            "ballerina_sources").toAbsolutePath();
    private static final Path DISTRIBUTION_PATH = Paths.get("../", "target", "ballerina-runtime")
            .toAbsolutePath();

    @Test
    public void testPluginInitialization() {
        WorkflowCompilerPlugin plugin = new WorkflowCompilerPlugin();
        Assert.assertNotNull(plugin);
    }

    // ===== Valid test cases =====

    @Test(groups = "valid")
    public void testValidWorkflowWithActivities() {
        String packagePath = "valid_process_with_activities";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for valid workflow with activities. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testWorkflowWithNoActivities() {
        String packagePath = "process_no_activities";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for workflow with no activities. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testMultipleWorkflowFunctions() {
        String packagePath = "multiple_processes";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for multiple workflow functions. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidWorkflowWithContext() {
        String packagePath = "valid_process_with_context";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for workflow with workflow:Context. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidNoArgActivity() {
        String packagePath = "valid_no_arg_activity";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for callActivity with empty args for no-arg activity. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidActivityTypedescDependent() {
        String packagePath = "valid_activity_typedesc_dependent";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for dependently-typed activity with inferred typedesc default. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    // ===== Invalid test cases - Validation errors =====

    @Test(groups = "invalid")
    public void testInvalidActivityTypedescDefault() {
        String packagePath = "invalid_activity_typedesc_default";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 1,
                "Expected exactly 1 validation error for activity with explicitly defaultable typedesc param");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_114);
    }

    @Test(groups = "invalid")
    public void testInvalidActivityTypedescRequired() {
        String packagePath = "invalid_activity_typedesc_required";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 1,
                "Expected exactly 1 validation error for activity with required typedesc param");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_114);
    }

    @Test(groups = "invalid")
    public void testInvalidActivityParam() {
        String packagePath = "invalid_activity_param";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for activity with non-anydata parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_103);
    }

    @Test(groups = "invalid")
    public void testInvalidActivityReturn() {
        String packagePath = "invalid_activity_return";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for activity with non-anydata return type");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_104);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowParam() {
        String packagePath = "invalid_process_param";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for workflow with non-anydata input parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_101);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowReturn() {
        String packagePath = "invalid_process_return";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for workflow with non-anydata return type");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_105);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowEvents() {
        String packagePath = "invalid_process_events";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for workflow with invalid events parameter type");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_102);
    }

    @Test(groups = "invalid")
    public void testInvalidCallActivityNoAnnotation() {
        String packagePath = "invalid_call_activity_no_annotation";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for callActivity with non-activity function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_107);
    }

    @Test(groups = "invalid")
    public void testInvalidDirectActivityCall() {
        String packagePath = "invalid_direct_activity_call";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for direct @Activity function call in @Workflow function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_108);
    }

    @Test(groups = "invalid")
    public void testInvalidCallActivityMissingParam() {
        String packagePath = "invalid_call_activity_missing_param";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for callActivity with missing required parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_109);
    }

    @Test(groups = "invalid")
    public void testInvalidNoArgActivityWithRequiredParams() {
        String packagePath = "invalid_no_arg_activity_with_required_params";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for callActivity with empty args when activity requires parameters");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_109);
    }

    @Test(groups = "invalid")
    public void testInvalidCallActivityExtraParam() {
        String packagePath = "invalid_call_activity_extra_param";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for callActivity with extra parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_110);
    }

    @Test(groups = "invalid")
    public void testInvalidCallActivityRestParams() {
        String packagePath = "invalid_call_activity_rest_params";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for callActivity with activity having rest parameters");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_111);
    }

    @Test(groups = "invalid")
    public void testInvalidWaitMultiple() {
        String packagePath = "invalid_wait_multiple";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for wait { ... } in @Workflow function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_115);
    }

    @Test(groups = "valid")
    public void testValidAwaitTyped() {
        String packagePath = "valid_wait_for_data_typed";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for valid typed ctx->await usage. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitNotFromEvents() {
        String packagePath = "invalid_wait_for_data_not_from_events";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for ctx->await futures not from events parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_116);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowWithWorker() {
        String packagePath = "invalid_workflow_worker";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for named worker declaration inside @Workflow function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_118);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowWithFork() {
        String packagePath = "invalid_workflow_fork";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for fork statement inside @Workflow function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_119);
    }

    @Test(groups = "invalid")
    public void testInvalidWorkflowWithStart() {
        String packagePath = "invalid_workflow_start";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for start action inside @Workflow function");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_120);
    }

    /**
     * Get diagnostic result for the given package path.
     * Uses runCodeGenAndModifyPlugins() to run the code modifier.
     *
     * @param packagePath the relative path to the test package
     * @return the diagnostic result
     */
    private DiagnosticResult getDiagnosticResult(String packagePath) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(packagePath);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        return project.currentPackage().runCodeGenAndModifyPlugins();
    }

    /**
     * Get diagnostic result for validation tests.
     * Runs the full compilation to get all diagnostics including CodeAnalyzer validation.
     *
     * @param packagePath the relative path to the test package
     * @return the diagnostic result
     */
    private DiagnosticResult getValidationDiagnosticResult(String packagePath) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(packagePath);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        // Get compilation diagnostics which include CodeAnalyzer results
        return project.currentPackage().getCompilation().diagnosticResult();
    }

    /**
     * Get the project environment builder with Ballerina home set.
     *
     * @return the project environment builder
     */
    private static ProjectEnvironmentBuilder getEnvironmentBuilder() {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        return ProjectEnvironmentBuilder.getBuilder(environment);
    }

    /**
     * Get all diagnostic messages as a single string for debugging.
     *
     * @param diagnosticResult the diagnostic result
     * @return a string containing all diagnostic messages
     */
    private String getDiagnosticMessages(DiagnosticResult diagnosticResult) {
        StringBuilder messages = new StringBuilder();
        for (Diagnostic diagnostic : diagnosticResult.diagnostics()) {
            messages.append("\n").append(diagnostic.diagnosticInfo().severity())
                    .append(": ").append(diagnostic.message())
                    .append(" at ").append(diagnostic.location().lineRange().startLine().line() + 1)
                    .append(":").append(diagnostic.location().lineRange().startLine().offset() + 1);
        }
        return messages.toString();
    }

    /**
     * Assert that the diagnostic result contains a diagnostic with the given code.
     *
     * @param diagnosticResult the diagnostic result
     * @param expectedDiagnostic the expected diagnostic enum
     */
    private void assertDiagnosticContains(DiagnosticResult diagnosticResult, WorkflowDiagnostic expectedDiagnostic) {
        assertDiagnosticContains(diagnosticResult, expectedDiagnostic.getCode());
    }

    /**
     * Assert that the diagnostic result contains a diagnostic with the given code.
     *
     * @param diagnosticResult the diagnostic result
     * @param expectedCode the expected diagnostic code
     */
    private void assertDiagnosticContains(DiagnosticResult diagnosticResult, String expectedCode) {
        boolean found = false;
        for (Diagnostic diagnostic : diagnosticResult.diagnostics()) {
            if (diagnostic.diagnosticInfo().code().equals(expectedCode)) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(found, "Expected diagnostic with code " + expectedCode + ". Got: "
                + getDiagnosticMessages(diagnosticResult));
    }

    // ===== sendData validation test cases =====

    @Test(groups = "valid")
    public void testValidSendEventWithExplicitSignalName() {
        String packagePath = "valid_send_event_with_signal_name";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData provides all required params. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidSendEventWithDistinctTypes() {
        String packagePath = "valid_send_event_distinct_types";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData is called with all required params. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidSendEventWithSingleSignal() {
        String packagePath = "valid_send_event_single_signal";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData is called with all required params. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidSendEventAmbiguousWithDataName() {
        String packagePath = "valid_send_event_with_data_name";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData provides dataName for ambiguous signals. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidSendEventThreeSignalsWithDataName() {
        String packagePath = "invalid_send_event_ambiguous_three_signals";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData provides dataName for three signals. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    // ===== sendData with workflowId =====

    @Test(groups = "valid")
    public void testValidSendDataWithWorkflowId() {
        String packagePath = "valid_send_signal_with_workflow_id";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData uses workflowId. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidSendDataNoCorrelation() {
        String packagePath = "invalid_send_signal_no_correlation";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when sendData is called with all required params. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }
}
