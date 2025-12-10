/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.directory.BuildProject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_100;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_101;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_102;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_103;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_104;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_105;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_106;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_107;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_108;
import static io.ballerina.stdlib.workflow.compiler.diagnostics.WorkflowDiagnostic.WORKFLOW_109;
import static io.ballerina.stdlib.workflow.compiler.util.WorkFlowAssertUtil.assertError;
import static io.ballerina.stdlib.workflow.compiler.util.WorkFlowTestUtil.getEnvironmentBuilder;

/**
 * Test class for workflow compiler plugin validation.
 * 
 * @since 0.1.0
 */
public class WorkflowCompilerPluginTest {
    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources",
                    "ballerina-workflow-validation")
            .toAbsolutePath();

    private Package loadPackage(String path) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(path);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        return project.currentPackage();
    }

    @Test
    public void testValidWorkFlowService() {
        Package currentPackage = loadPackage("valid_workflow_service");
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();
        Assert.assertEquals(diagnosticResult.errorCount(), 0);
    }

    @Test
    public void testInValidReturnTypes() {
        Package currentPackage = loadPackage("invalid_workflow_service");
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();
        Assert.assertEquals(diagnosticResult.errorCount(), 9);
        int i = 0;
        assertError(diagnosticResult, i++, "remote methods in a workflow service should be " +
                "annotated with `@workflow:Signal` or `@workflow:Query` annotation", WORKFLOW_103, 46, 5);
        assertError(diagnosticResult, i++, "resource methods are not allowed in a workflow service",
                WORKFLOW_100, 54, 5);
        assertError(diagnosticResult, i++, "workflow service should contain remote method `execute`",
                WORKFLOW_101, 40, 1);
        assertError(diagnosticResult, i++, "parameters of a remote method in a workflow " +
                "service should be a subtype of `anydata`", WORKFLOW_102, 62, 47);
        assertError(diagnosticResult, i++, "remote methods in a workflow service should be isolated",
                WORKFLOW_104, 65, 5);
        assertError(diagnosticResult, i++, "parameters of a remote method in a workflow service " +
                "should be a subtype of `anydata`", WORKFLOW_102, 66, 36);
        assertError(diagnosticResult, i++, "remote methods in a workflow service should be annotated " +
                "with `@workflow:Signal` or `@workflow:Query` annotation", WORKFLOW_103, 69, 5);
        assertError(diagnosticResult, i++, "return type of a remote method in a workflow service " +
                "should be a subtype of `error?`", WORKFLOW_106, 72, 5);
        assertError(diagnosticResult, i++, "return type of a remote method annotated with " +
                "`@workflow:Query` should be a subtype of `anydata|error`", WORKFLOW_105, 77, 5);
    }

    @Test
    public void testMutableGlobalVariableAccess() {
        Package currentPackage = loadPackage("mutable_global_access");
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();
        Assert.assertEquals(diagnosticResult.errorCount(), 7);
        int i = 0;
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 49, 28);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 53, 25);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 57, 34);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 61, 34);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 65, 35);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 69, 35);
        assertError(diagnosticResult, i++, "execute function cannot access mutable global variables",
                WORKFLOW_108, 74, 35);
    }

    @Test
    public void testVarBindingWithActivityFunctionCall() {
        Package currentPackage = loadPackage("var_binding_activity_call");
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();
        Assert.assertEquals(diagnosticResult.errorCount(), 4);
        int i = 0;
        assertError(diagnosticResult, i++, "cannot use var binding pattern when calling an activity function",
                WORKFLOW_109, 56, 9);
        assertError(diagnosticResult, i++, "cannot use var binding pattern when calling an activity function",
                WORKFLOW_109, 57, 9);
        assertError(diagnosticResult, i++, "cannot use var binding pattern when calling an activity function",
                WORKFLOW_109, 58, 9);
        assertError(diagnosticResult, i++, "cannot use var binding pattern when calling an activity function",
                WORKFLOW_109, 59, 9);
    }

    @Test
    public void testActivityFunctionParameterValidation() {
        Package currentPackage = loadPackage("invalid_activity_parameters");
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();
        Assert.assertEquals(diagnosticResult.errorCount(), 4);
        int i = 0;
        assertError(diagnosticResult, i++, "parameters of an activity function should be a subtype of `anydata`",
                WORKFLOW_107, 21, 63);
        assertError(diagnosticResult, i++, "parameters of an activity function should be a subtype of `anydata`",
                WORKFLOW_107, 30, 45);
        assertError(diagnosticResult, i++, "parameters of an activity function should be a subtype of `anydata`",
                WORKFLOW_107, 35, 46);
        assertError(diagnosticResult, i++, "parameters of an activity function should be a subtype of `anydata`",
                WORKFLOW_107, 44, 42);
    }
}
