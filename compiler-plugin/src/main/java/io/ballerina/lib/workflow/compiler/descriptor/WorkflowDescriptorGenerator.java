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
 * Builds the Workflow Definition Descriptor while the semantic model is live, and hands it to
 * {@link DescriptorPackLifecycleTask} through {@link WorkflowDescriptorStore} to be written into
 * the executable JAR as the root-level entry {@code workflow.def.json} — the externally
 * consumable, fixed-name artifact of the descriptor spec.
 * <p>
 * The document is <em>not</em> registered as a package resource: measured on 2201.13.4,
 * {@code SourceGeneratorContext.addResourceFile} puts nothing into the executable, the BALA, or
 * {@code target}, whereas a file physically present in a package's {@code resources} directory is
 * packed into both. Only executables carry the descriptor; a BALA does not.
 * <p>
 * This is also NOT the runtime's registration source: packed resources never reach the
 * {@code bal test} classpath, so {@link io.ballerina.lib.workflow.compiler.WorkflowSourceModifier}
 * embeds the same canonical document (byte-identical: both come from
 * {@link WorkflowDescriptorBuilder#build}, which is deterministic) as data in the generated
 * registration, and the runtime prefers that registered document over the classpath copy.
 *
 * @since 0.9.0
 */
public class WorkflowDescriptorGenerator extends CodeGenerator {

    /** Reported when two declarations claim the same descriptor name. */
    private static final String NAME_COLLISION_CODE = "WORKFLOW_DESCRIPTOR_NAME_COLLISION";

    private final WorkflowDescriptorStore store;

    public WorkflowDescriptorGenerator(WorkflowDescriptorStore store) {
        this.store = store;
    }

    @Override
    public void init(CodeGeneratorContext generatorContext) {
        generatorContext.addSourceGeneratorTask(new DescriptorCollectTask(store));
    }

    private static final class DescriptorCollectTask implements GeneratorTask<SourceGeneratorContext> {

        private final WorkflowDescriptorStore store;

        private DescriptorCollectTask(WorkflowDescriptorStore store) {
            this.store = store;
        }

        @Override
        public void generate(SourceGeneratorContext context) {
            // Built here because the semantic model is live during code generation, and handed
            // to DescriptorPackLifecycleTask, which writes it once the JAR exists. Resources
            // added through addResourceFile at this point never reach the built artifact, so
            // the bytes travel through the store instead.
            byte[] descriptor = WorkflowDescriptorBuilder.build(
                    context.currentPackage(), context.compilation(), false,
                    message -> context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                            new DiagnosticInfo(NAME_COLLISION_CODE, message,
                                    DiagnosticSeverity.ERROR),
                            new NullLocation())));
            store.setDescriptorBytes(descriptor);
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
