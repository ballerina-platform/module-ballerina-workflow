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

package io.ballerina.stdlib.workflow.compiler;

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
import java.util.Iterator;

/**
 * Tests for workflow compiler plugin.
 * Tests the code modifier that detects @Process functions and transforms @Activity calls.
 * Also tests the validator that checks @Process and @Activity function signatures.
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
    public void testValidProcessWithActivities() {
        String packagePath = "valid_process_with_activities";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for valid process with activities. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testProcessWithNoActivities() {
        String packagePath = "process_no_activities";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for process with no activities. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testMultipleProcessFunctions() {
        String packagePath = "multiple_processes";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for multiple process functions. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    @Test(groups = "valid")
    public void testValidProcessWithContext() {
        String packagePath = "valid_process_with_context";
        DiagnosticResult diagnosticResult = getDiagnosticResult(packagePath);
        Assert.assertEquals(diagnosticResult.errorCount(), 0,
                "Expected no errors for process with workflow:Context. Errors: "
                        + getDiagnosticMessages(diagnosticResult));
    }

    // ===== Invalid test cases - Validation errors =====

    @Test(groups = "invalid")
    public void testInvalidActivityParam() {
        String packagePath = "invalid_activity_param";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for activity with non-anydata parameter");
        assertDiagnosticContains(diagnosticResult, WorkflowConstants.WORKFLOW_103);
    }

    @Test(groups = "invalid")
    public void testInvalidActivityReturn() {
        String packagePath = "invalid_activity_return";
        DiagnosticResult diagnosticResult = getValidationDiagnosticResult(packagePath);
        Assert.assertTrue(diagnosticResult.errorCount() > 0,
                "Expected validation error for activity with non-anydata return type");
        assertDiagnosticContains(diagnosticResult, WorkflowConstants.WORKFLOW_104);
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
        Iterator<Diagnostic> diagnostics = diagnosticResult.diagnostics().iterator();
        while (diagnostics.hasNext()) {
            Diagnostic diagnostic = diagnostics.next();
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
}
