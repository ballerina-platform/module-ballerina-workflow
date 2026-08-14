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

package io.ballerina.lib.workflow.compiler.descriptor;

import io.ballerina.projects.plugins.CodeGenerator;
import io.ballerina.projects.plugins.CodeGeneratorContext;
import io.ballerina.projects.plugins.GeneratorTask;
import io.ballerina.projects.plugins.SourceGeneratorContext;

/**
 * Generates the Workflow Definition Descriptor and packs it as the package resource
 * {@code workflow.def.json}. A package resource travels everywhere the compiled package does —
 * the executable JAR, the BALA, and the {@code bal test} test artifacts — landing on the
 * runtime classpath as {@code resources/workflow.def.json}, which is where the workflow
 * runtime's descriptor loader reads it from. (A build-completed lifecycle task could only
 * write into the executable JAR, leaving {@code bal test} runs without a descriptor.)
 *
 * @since 0.9.0
 */
public class WorkflowDescriptorGenerator extends CodeGenerator {

    /** The packed resource's file name; on the classpath it appears under {@code resources/}. */
    public static final String DESCRIPTOR_FILE_NAME = "workflow.def.json";

    @Override
    public void init(CodeGeneratorContext generatorContext) {
        generatorContext.addSourceGeneratorTask(new DescriptorResourceTask());
    }

    private static final class DescriptorResourceTask implements GeneratorTask<SourceGeneratorContext> {
        @Override
        public void generate(SourceGeneratorContext context) {
            byte[] descriptor = WorkflowDescriptorBuilder.build(
                    context.currentPackage(), context.compilation());
            if (descriptor != null) {
                context.addResourceFile(descriptor, DESCRIPTOR_FILE_NAME);
            }
        }
    }
}
