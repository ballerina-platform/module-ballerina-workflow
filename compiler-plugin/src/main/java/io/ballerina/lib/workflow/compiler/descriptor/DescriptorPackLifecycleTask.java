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

import io.ballerina.projects.plugins.CompilerLifecycleEventContext;
import io.ballerina.projects.plugins.CompilerLifecycleTask;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.diagnostics.Location;
import io.ballerina.tools.text.LinePosition;
import io.ballerina.tools.text.LineRange;
import io.ballerina.tools.text.TextRange;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the descriptor built by {@link WorkflowDescriptorBuilder} into the generated executable
 * JAR as a root-level {@code workflow.def.json} entry (executables only — see
 * {@link #perform} for why a BALA is skipped) — a single fixed name, never scanned for, so the
 * read side stays compatible with GraalVM native-image resource rules. Runs after code
 * generation has completed: by then the semantic model is no longer safely queryable, which is
 * why the document is built earlier and only the bytes are written here (the same split the
 * ICP bridge's swagger-pack uses).
 *
 * @since 0.9.0
 */
public class DescriptorPackLifecycleTask implements CompilerLifecycleTask<CompilerLifecycleEventContext> {

    /** The fixed JAR entry name of the packed descriptor. */
    public static final String DESCRIPTOR_RESOURCE = "workflow.def.json";

    private static final String IO_ERROR_CODE = "WORKFLOW_DESCRIPTOR_IO";

    private static final String JAR_EXTENSION = ".jar";

    private final WorkflowDescriptorStore store;

    public DescriptorPackLifecycleTask(WorkflowDescriptorStore store) {
        this.store = store;
    }

    @Override
    public void perform(CompilerLifecycleEventContext context) {
        if (store.isEmpty()) {
            return;
        }
        Optional<Path> artifactPath = context.getGeneratedArtifactPath();
        if (artifactPath.isEmpty()) {
            return;
        }
        Path artifact = artifactPath.get();
        // This hook also fires for artifacts that are not executables — `bal pack` reports the
        // BALA it is building, which is not open for writing at this point (attempting it fails
        // the whole build). Only the executable JAR carries the packed descriptor; a consumer
        // that needs it from a BALA regenerates it from the package.
        if (!artifact.toString().endsWith(JAR_EXTENSION) || !Files.isRegularFile(artifact)) {
            return;
        }
        try {
            packInto(artifact, store.descriptorBytes());
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                    new DiagnosticInfo(IO_ERROR_CODE,
                            "failed to pack the workflow descriptor into " + artifact
                                    + ": " + detail,
                            DiagnosticSeverity.ERROR),
                    new NullLocation()));
        }
    }

    /**
     * Writes {@code descriptor} into the JAR at {@code artifact} as the root-level entry
     * {@value #DESCRIPTOR_RESOURCE}, replacing any existing entry of that name.
     */
    public static void packInto(Path artifact, byte[] descriptor) throws IOException {
        URI jarUri = URI.create("jar:" + artifact.toUri());
        try (FileSystem jarFs = FileSystems.newFileSystem(jarUri, Map.of("create", "false"))) {
            Files.write(jarFs.getPath(DESCRIPTOR_RESOURCE), descriptor,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
