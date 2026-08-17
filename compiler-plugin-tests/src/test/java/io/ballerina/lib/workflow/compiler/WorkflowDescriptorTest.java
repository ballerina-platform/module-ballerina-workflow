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

import io.ballerina.lib.workflow.compiler.descriptor.DescriptorPackLifecycleTask;
import io.ballerina.lib.workflow.compiler.descriptor.WorkflowDescriptorBuilder;
import io.ballerina.lib.workflow.compiler.descriptor.WorkflowDescriptorStore;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final String DESCRIPTOR_JAR_ENTRY = "workflow.def.json";

    static {
        // JBallerinaBackend's interop validation resolves the runtime JAR through this property.
        System.setProperty("ballerina.home", DISTRIBUTION_PATH.toString());
    }

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

    // The artifact external tooling reads. Emitting an executable from this harness cannot
    // exercise the packing itself: the descriptor is injected by a compiler-lifecycle task, and
    // `codeGenerationCompleted` fires in the CLI build flow, not when a test calls
    // `JBallerinaBackend.emit` directly. So the two halves are asserted separately — the
    // injection here, and the hand-off between plugin phases in
    // `testDescriptorStoreCrossesPluginPhases`. End-to-end packing is verified by building a
    // consumer package with `bal build`, where the entry appears at the JAR root.
    @Test
    public void testDescriptorIsPackedIntoAnEmittedJar() throws IOException {
        BuildProject project = loadProject("descriptor_generation");
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());

        Path execJar = Files.createTempDirectory("wf-descriptor-pack").resolve("packed.jar");
        JBallerinaBackend backend = JBallerinaBackend.from(compilation, JvmTarget.JAVA_21);
        backend.emit(JBallerinaBackend.OutputType.EXEC, execJar);

        String expected = buildDescriptor("descriptor_generation");
        DescriptorPackLifecycleTask.packInto(execJar, expected.getBytes(StandardCharsets.UTF_8));

        try (ZipFile jar = new ZipFile(execJar.toFile())) {
            ZipEntry entry = jar.getEntry(DESCRIPTOR_JAR_ENTRY);
            Assert.assertNotNull(entry, DESCRIPTOR_JAR_ENTRY + " is missing from " + execJar);
            String packed = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Assert.assertEquals(packed, expected,
                    "The packed descriptor must be the document the builder produced");
        }
    }

    // The generator and the lifecycle task run in different plugin instances, so a field on the
    // shared store is invisible across them — the bytes have to travel through the plugin's user
    // data map. This pins that contract: a store built over the same map sees what the other
    // store wrote, which is exactly what a store-per-phase does at build time.
    @Test
    public void testDescriptorStoreCrossesPluginPhases() {
        Map<String, Object> userData = new HashMap<>();
        WorkflowDescriptorStore generatorSide = new WorkflowDescriptorStore(userData);
        WorkflowDescriptorStore lifecycleSide = new WorkflowDescriptorStore(userData);

        Assert.assertTrue(lifecycleSide.isEmpty(), "Nothing has been generated yet");

        byte[] descriptor = "{\"workflows\":[]}".getBytes(StandardCharsets.UTF_8);
        generatorSide.setDescriptorBytes(descriptor);

        Assert.assertFalse(lifecycleSide.isEmpty(),
                "The lifecycle task must see the document the generator built");
        Assert.assertEquals(lifecycleSide.descriptorBytes(), descriptor);
        Assert.assertEquals(new WorkflowDescriptorStore(new HashMap<>()).descriptorBytes().length, 0,
                "A store with no generated document reports zero bytes, not null");
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
