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

import io.ballerina.lib.workflow.compiler.descriptor.WorkflowDescriptorBuilder;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Tests the Workflow Definition Descriptor: the builder's canonical document (golden
 * comparison, checksum, determinism) and the generated registration hand-off — the source
 * modifier embeds the document as data in a single {@code registerWorkflowDescriptor} call,
 * replacing the former per-workflow {@code registerWorkflow}/{@code registerHumanTask} codegen.
 *
 * @since 0.9.0
 */
public class WorkflowDescriptorTest {

    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources",
            "ballerina_sources").toAbsolutePath();
    private static final Path GOLDEN_DIRECTORY = Paths.get("src", "test", "resources",
            "descriptors").toAbsolutePath();
    private static final Path DISTRIBUTION_PATH = Paths.get("../", "target", "ballerina-runtime")
            .toAbsolutePath();
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("\"checksum\":\"sha256:[0-9a-f]{64}\"");

    @Test
    public void testDescriptorMatchesGolden() throws IOException {
        String descriptor = buildDescriptor("descriptor_generation");

        Assert.assertTrue(CHECKSUM_PATTERN.matcher(descriptor).find(),
                "Descriptor has no sha256 checksum: " + descriptor);

        Path goldenPath = GOLDEN_DIRECTORY.resolve("descriptor_generation.json");
        if (!Files.exists(goldenPath)) {
            // Bootstrap aid: write the actual output next to the expected location and fail.
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath.resolveSibling("descriptor_generation.actual.json"), descriptor);
            Assert.fail("Golden file missing: " + goldenPath + " — actual output written alongside");
        }
        String golden = Files.readString(goldenPath, StandardCharsets.UTF_8).strip();
        Assert.assertEquals(descriptor, golden,
                "Descriptor does not match the golden document");
    }

    @Test
    public void testDescriptorIsDeterministic() throws IOException {
        Assert.assertEquals(buildDescriptor("descriptor_generation"),
                buildDescriptor("descriptor_generation"),
                "Descriptor must be byte-stable across builds");
    }

    @Test
    public void testGeneratedRegistrationEmbedsDescriptor() {
        BuildProject project = loadProject("descriptor_generation");
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());

        String modifiedSources = allSourcesOf(project);
        String marker = ":registerWorkflowDescriptor(\"";
        int start = modifiedSources.indexOf(marker);
        Assert.assertTrue(start >= 0,
                "Generated registration must hand the descriptor to the runtime as data");
        Assert.assertFalse(modifiedSources.contains(":registerWorkflow("),
                "Per-workflow registerWorkflow codegen must be gone");
        Assert.assertFalse(modifiedSources.contains(":registerHumanTask("),
                "Per-task registerHumanTask codegen must be gone");
        // The embedded string literal itself must carry the structural facts the runtime
        // registers from — the workflow names elsewhere in the sources don't count.
        int literalStart = start + marker.length();
        int literalEnd = modifiedSources.indexOf("\");", literalStart);
        Assert.assertTrue(literalEnd > literalStart, "Embedded descriptor literal not terminated");
        String embedded = modifiedSources.substring(literalStart, literalEnd);
        Assert.assertTrue(embedded.contains("expenseApproval") && embedded.contains("managerApproval")
                        && embedded.contains("descriptorVersion"),
                "The embedded descriptor must describe the package's workflows, got: "
                        + embedded.substring(0, Math.min(200, embedded.length())));
    }

    private String buildDescriptor(String packageName) {
        BuildProject project = loadProject(packageName);
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());
        byte[] descriptor = WorkflowDescriptorBuilder.build(project.currentPackage(), compilation);
        Assert.assertNotNull(descriptor, "The package declares workflows; a descriptor must be built");
        return new String(descriptor, StandardCharsets.UTF_8);
    }

    private static String allSourcesOf(BuildProject project) {
        StringBuilder sources = new StringBuilder();
        for (ModuleId moduleId : project.currentPackage().moduleIds()) {
            Module module = project.currentPackage().module(moduleId);
            for (DocumentId documentId : module.documentIds()) {
                sources.append(module.document(documentId).syntaxTree().toSourceCode());
            }
        }
        return sources.toString();
    }

    private static BuildProject loadProject(String packageName) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(packageName);
        return BuildProject.load(getEnvironmentBuilder(), projectDirPath);
    }

    private static ProjectEnvironmentBuilder getEnvironmentBuilder() {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        return ProjectEnvironmentBuilder.getBuilder(environment);
    }
}
