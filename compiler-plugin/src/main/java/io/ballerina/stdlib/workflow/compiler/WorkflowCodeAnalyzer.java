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

import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.CodeAnalysisContext;
import io.ballerina.projects.plugins.CodeAnalyzer;

/**
 * Code analyzer for validating workflow @Process and @Activity function signatures.
 * <p>
 * This analyzer validates:
 * <ul>
 *   <li>@Process functions have valid signature: (Context?, anydata input, record{future<anydata>...} events?)</li>
 *   <li>@Activity functions have anydata parameters and anydata|error return type</li>
 *   <li>sendEvent calls have explicit signalName when events record has ambiguous types</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class WorkflowCodeAnalyzer extends CodeAnalyzer {

    @Override
    public void init(CodeAnalysisContext analysisContext) {
        // Add syntax node analysis task for function definitions
        analysisContext.addSyntaxNodeAnalysisTask(new WorkflowValidatorTask(), SyntaxKind.FUNCTION_DEFINITION);
        
        // Add syntax node analysis task for function calls to validate sendEvent usage
        analysisContext.addSyntaxNodeAnalysisTask(new SendEventValidatorTask(), SyntaxKind.FUNCTION_CALL);
    }
}
