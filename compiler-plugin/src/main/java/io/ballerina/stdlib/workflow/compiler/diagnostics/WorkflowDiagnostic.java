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

package io.ballerina.stdlib.workflow.compiler.diagnostics;

import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import static io.ballerina.tools.diagnostics.DiagnosticSeverity.ERROR;

/**
 * Compilation error codes used in Ballerina Workflow package compiler plugin.
 */
public enum WorkflowDiagnostic {
    WORKFLOW_100("WORKFLOW_100", "resource methods are not allowed in a workflow service", ERROR),
    WORKFLOW_101("WORKFLOW_101", "workflow service should contain remote method `execute`", ERROR),
    WORKFLOW_102("WORKFLOW_102",
            "parameters of a remote method in a workflow service should be a subtype of `anydata`", ERROR),
    WORKFLOW_103("WORKFLOW_103",
            "remote methods in a workflow service should be annotated with " +
                    "`@workflow:Signal` or `@workflow:Query` annotation", ERROR),
    WORKFLOW_104("WORKFLOW_104", "remote methods in a workflow service should be isolated", ERROR),
    WORKFLOW_105("WORKFLOW_105", "return type of a remote method annotated with " +
            "`@workflow:Query` should be a subtype of `anydata|error`", ERROR),
    WORKFLOW_106("WORKFLOW_106", "return type of a remote method in a " +
            "workflow service should be a subtype of `error?`", ERROR),
    WORKFLOW_107("WORKFLOW_107",
            "parameters of an activity function should be a subtype of `anydata`", ERROR),
    WORKFLOW_108("WORKFLOW_108",
            "execute function cannot access mutable global variables", ERROR),
    WORKFLOW_109("WORKFLOW_109",
            "cannot use var binding pattern when calling an activity function", ERROR);

    private final String code;
    private final String message;
    private final DiagnosticSeverity severity;

    WorkflowDiagnostic(String code, String message, DiagnosticSeverity severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public DiagnosticSeverity getSeverity() {
        return severity;
    }
}
