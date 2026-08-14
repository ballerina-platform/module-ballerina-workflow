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

import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tests that the compiler plugin builds the Workflow Definition Descriptor and packs it into
 * the generated executable JAR as {@code workflow.def.json}, with the expected canonical
 * content (golden comparison) and a checksum computed over the canonical bytes.
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

    static {
        // JBallerinaBackend's interop validation resolves the runtime JAR through this property.
        System.setProperty("ballerina.home", DISTRIBUTION_PATH.toString());
    }

    @Test
    public void testDescriptorPackedIntoExecutableJar() throws IOException {
        String descriptor = buildAndExtractDescriptor("descriptor_generation");

        Matcher checksum = CHECKSUM_PATTERN.matcher(descriptor);
        Assert.assertTrue(checksum.find(), "Descriptor has no sha256 checksum: " + descriptor);

        Path goldenPath = GOLDEN_DIRECTORY.resolve("descriptor_generation.json");
        if (!Files.exists(goldenPath)) {
            // Bootstrap aid: write the actual output next to the expected location and fail.
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath.resolveSibling("descriptor_generation.actual.json"), descriptor);
            Assert.fail("Golden file missing: " + goldenPath + " — actual output written alongside");
        }
        String golden = Files.readString(goldenPath, StandardCharsets.UTF_8).strip();
        Assert.assertEquals(descriptor, golden,
                "Packed descriptor does not match the golden document");
    }

    @Test
    public void testDescriptorIsDeterministic() throws IOException {
        String first = buildAndExtractDescriptor("descriptor_generation");
        String second = buildAndExtractDescriptor("descriptor_generation");
        Assert.assertEquals(first, second, "Descriptor must be byte-stable across builds");
    }

    private String buildAndExtractDescriptor(String packageName) throws IOException {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(packageName);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());

        Path execJar = Files.createTempDirectory("wf-descriptor-test").resolve(packageName + ".jar");
        JBallerinaBackend backend = JBallerinaBackend.from(compilation, JvmTarget.JAVA_21);
        backend.emit(JBallerinaBackend.OutputType.EXEC, execJar);

        try (ZipFile jar = new ZipFile(execJar.toFile())) {
            ZipEntry entry = jar.getEntry("workflow.def.json");
            Assert.assertNotNull(entry, "workflow.def.json not packed into " + execJar);
            return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ProjectEnvironmentBuilder getEnvironmentBuilder() {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        return ProjectEnvironmentBuilder.getBuilder(environment);
    }
}
