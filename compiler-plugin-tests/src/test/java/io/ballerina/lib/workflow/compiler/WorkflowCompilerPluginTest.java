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
import java.util.ArrayList;
import java.util.List;

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

        @Test(groups = "valid")
        public void testValidCallActivityWithModuleFinalClientArg() {
        String packagePath = "valid_call_activity_client_module_final";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
            "Expected no validation errors for module-level final client argument. Errors: "
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
    public void testInvalidCallActivityClientArgNonReference() {
        String packagePath = "invalid_call_activity_client_non_reference";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 3,
                "Expected exactly 3 validation errors for non-reference client argument");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_124);
    }

    @Test(groups = "invalid")
    public void testInvalidCallActivityClientArgNotModuleFinal() {
        String packagePath = "invalid_call_activity_client_not_module_final";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 1,
            "Expected exactly 1 validation error for client argument that is not module-level final/configurable");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_125);
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

    @Test(groups = "valid")
    public void testValidAwaitWithTimeout() {
        String packagePath = "valid_await_with_timeout";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for ctx->await with timeout parameter. Errors: "
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

    @Test(groups = "invalid")
    public void testInvalidAwaitScalarTypeMismatch() {
        String packagePath = "invalid_await_scalar_type_mismatch";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for scalar type mismatch in ctx->await with single future");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_121);
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitScalarMultiFuture() {
        String packagePath = "invalid_await_scalar_multi_future";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for scalar type used with multiple futures in ctx->await");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_122);
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPrimitiveTypeMismatch() {
        String packagePath = "invalid_await_primitive_type_mismatch";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for string result from future<int>");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_121);
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitRecordMismatch() {
        String packagePath = "invalid_await_record_mismatch";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for ApprovalDecision result from future<PaymentInfo>");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_121);
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitTupleSwapped() {
        String packagePath = "invalid_await_tuple_swapped";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_117");
        Assert.assertEquals(diags.size(), 2, "Expected 2 WORKFLOW_117 errors (both positions swapped)");
        // Errors should point at LHS tuple type members on line 43
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 43);
            assertMessageContains(d, "Return type mismatch");
        }
        assertMessageContains(diags.get(0), "position 0");
        assertMessageContains(diags.get(1), "position 1");
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitTupleWrongMember() {
        String packagePath = "invalid_await_tuple_wrong_member";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_117");
        // Only position 1 is wrong (AuditDecision vs ComplianceDecision)
        Assert.assertEquals(diags.size(), 1, "Expected 1 WORKFLOW_117 error at position 1");
        assertDiagnosticLine(diags.get(0), 48);
        assertMessageContains(diags.get(0), "position 1");
        assertMessageContains(diags.get(0), "Return type mismatch");
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitScalarThreeFutures() {
        String packagePath = "invalid_await_scalar_three_futures";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for boolean scalar type used with 3 futures");
        assertDiagnosticContains(diagnosticResult, WorkflowDiagnostic.WORKFLOW_122);
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPartialNotNilable() {
        String packagePath = "invalid_await_partial_not_nilable";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_123");
        Assert.assertEquals(diags.size(), 3, "Expected 3 WORKFLOW_123 errors (one per non-nilable member)");
        // All errors should point at the LHS tuple type on line 45
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 45);
        }
        // Verify messages contain position index and minCount/futureCount info
        assertMessageContains(diags.get(0), "position 0");
        assertMessageContains(diags.get(1), "position 1");
        assertMessageContains(diags.get(2), "position 2");
        for (Diagnostic d : diags) {
            assertMessageContains(d, "minCount (2)");
            assertMessageContains(d, "futures (3)");
            assertMessageContains(d, "nilable");
        }
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPartialNamedMinCount() {
        String packagePath = "invalid_await_partial_named_mincount";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_123");
        Assert.assertEquals(diags.size(), 2, "Expected 2 WORKFLOW_123 errors");
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 39);
            assertMessageContains(d, "minCount (1)");
            assertMessageContains(d, "futures (2)");
        }
        assertMessageContains(diags.get(0), "position 0");
        assertMessageContains(diags.get(1), "position 1");
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPartialMixedNilable() {
        String packagePath = "invalid_await_partial_mixed_nilable";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_123");
        // Position 0 is nilable (ApprovalDecision?), so only positions 1 and 2 should error
        Assert.assertEquals(diags.size(), 2, "Expected 2 WORKFLOW_123 errors (positions 1 and 2 only)");
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 46);
            assertMessageContains(d, "minCount (1)");
            assertMessageContains(d, "futures (3)");
        }
        assertMessageContains(diags.get(0), "position 1");
        assertMessageContains(diags.get(1), "position 2");
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPartialNoBinding() {
        String packagePath = "invalid_await_partial_no_binding";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_123");
        Assert.assertEquals(diags.size(), 2, "Expected 2 WORKFLOW_123 errors");
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 44);
            assertMessageContains(d, "minCount (1)");
            assertMessageContains(d, "futures (2)");
        }
        assertMessageContains(diags.get(0), "position 0");
        assertMessageContains(diags.get(1), "position 1");
    }

    @Test(groups = "invalid")
    public void testInvalidAwaitPartialTwoFutures() {
        String packagePath = "invalid_await_partial_two_futures";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        List<Diagnostic> diags = getDiagnosticsWithCode(diagnosticResult, "WORKFLOW_123");
        Assert.assertEquals(diags.size(), 2, "Expected 2 WORKFLOW_123 errors");
        for (Diagnostic d : diags) {
            assertDiagnosticLine(d, 43);
            assertMessageContains(d, "minCount (1)");
            assertMessageContains(d, "futures (2)");
        }
        assertMessageContains(diags.get(0), "position 0");
        assertMessageContains(diags.get(1), "position 1");
    }

    @Test(groups = "valid")
    public void testValidAwaitPartialNilableNoBinding() {
        String packagePath = "valid_await_partial_nilable_no_binding";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for nilable tuple without binding pattern. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidAwaitPartialNamedMinCount() {
        String packagePath = "valid_await_partial_named_mincount";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for nilable tuple with named minCount arg. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidAwaitMinCountEqualsFutures() {
        String packagePath = "valid_await_mincount_equals_futures";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors when minCount equals future count (non-nilable is fine). Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidAwaitPartialMixedTypes() {
        String packagePath = "valid_await_partial_mixed_types";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for partial wait with mixed types, all nilable. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
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

    /**
     * Get all diagnostics with the given code.
     *
     * @param diagnosticResult the diagnostic result
     * @param code the diagnostic code
     * @return list of matching diagnostics
     */
    private List<Diagnostic> getDiagnosticsWithCode(DiagnosticResult diagnosticResult, String code) {
        List<Diagnostic> matching = new ArrayList<>();
        for (Diagnostic diagnostic : diagnosticResult.diagnostics()) {
            if (diagnostic.diagnosticInfo().code().equals(code)) {
                matching.add(diagnostic);
            }
        }
        return matching;
    }

    /**
     * Assert that a diagnostic has the expected line number (1-based).
     *
     * @param diagnostic the diagnostic
     * @param expectedLine the expected 1-based line number
     */
    private void assertDiagnosticLine(Diagnostic diagnostic, int expectedLine) {
        int actualLine = diagnostic.location().lineRange().startLine().line() + 1;
        Assert.assertEquals(actualLine, expectedLine,
                "Diagnostic '" + diagnostic.diagnosticInfo().code() + "' expected at line "
                        + expectedLine + " but found at line " + actualLine
                        + ". Message: " + diagnostic.message());
    }

    /**
     * Assert that a diagnostic message contains the given substring.
     *
     * @param diagnostic the diagnostic
     * @param substring the expected substring
     */
    private void assertMessageContains(Diagnostic diagnostic, String substring) {
        Assert.assertTrue(diagnostic.message().contains(substring),
                "Expected message to contain '" + substring + "' but got: " + diagnostic.message());
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
