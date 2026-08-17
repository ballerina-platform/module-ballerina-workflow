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
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.diagnostics.Location;
import io.ballerina.tools.text.LinePosition;
import io.ballerina.tools.text.LineRange;
import io.ballerina.tools.text.TextRange;

/**
 * Packs the Workflow Definition Descriptor into build artifacts as the package resource
 * {@code workflow.def.json} (jar-root entry in the executable JAR, and part of the BALA) —
 * the externally consumable, fixed-name artifact of the descriptor spec. This is NOT the
 * runtime's registration source: packed resources never reach the {@code bal test}
 * classpath, so {@link io.ballerina.lib.workflow.compiler.WorkflowSourceModifier} embeds the
 * same canonical document (byte-identical: both come from
 * {@link WorkflowDescriptorBuilder#build}, which is deterministic) as data in the generated
 * registration, and the runtime prefers that registered document over the classpath copy.
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
                    context.currentPackage(), context.compilation(), false,
                    message -> context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                            new DiagnosticInfo("WORKFLOW_DESCRIPTOR_NAME_COLLISION", message,
                                    DiagnosticSeverity.ERROR),
                            new NullLocation())));
            if (descriptor != null) {
                context.addResourceFile(descriptor, DESCRIPTOR_FILE_NAME);
            }
        }
    }

    private static final class NullLocation implements Location {
        @Override
        public LineRange lineRange() {
            LinePosition from = LinePosition.from(0, 0);
            return LineRange.from("", from, from);
        }

        @Override
        public TextRange textRange() {
            return TextRange.from(0, 0);
        }
    }
}
