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

import io.ballerina.projects.BuildOptions;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The management API's exported artifacts: the curated OpenAPI description must be written
 * out under {@code --export-openapi}, and — because it is curated rather than generated —
 * must not be allowed to drift from the service it describes.
 */
public class ManagementApiArtifactExportTest {

    private static final Path RESOURCE_DIRECTORY =
            Paths.get("src", "test", "resources", "ballerina_sources").toAbsolutePath();
    private static final Path DISTRIBUTION_PATH =
            Paths.get("../", "target", "ballerina-runtime").toAbsolutePath();
    private static final Path MANAGEMENT_SERVICE_SOURCE =
            Paths.get("..", "ballerina", "modules", "management.rest", "service.bal").toAbsolutePath();

    @Test
    public void testExportOpenApiWritesTheManagementSpec() throws IOException {
        // The fixture imports workflow.management.rest — the module that owns the service —
        // so this package genuinely can serve the API the exported spec describes.
        Path specPath = emitWithOptions("management_rest_consumer",
                BuildOptions.builder().setExportOpenAPI(true).build(), "wf-openapi-export");

        Assert.assertTrue(Files.exists(specPath),
                "--export-openapi must write the management spec beside the package's own: " + specPath);
        String yaml = Files.readString(specPath, StandardCharsets.UTF_8);
        Assert.assertTrue(yaml.contains("Ballerina Workflow Management API"),
                "The exported file is the curated management spec");
    }

    @Test
    public void testWithoutTheFlagNothingIsExported() throws IOException {
        Path specPath = emitWithOptions("management_rest_consumer", null, "wf-openapi-noflag");
        Assert.assertFalse(Files.exists(specPath),
                "The spec is an explicit opt-in; a plain build must not write it");
    }

    @Test
    public void testWithoutTheImportNothingIsExported() throws IOException {
        // descriptor_generation imports only ballerina/workflow. Without
        // workflow.management.rest there is no service object and no listener, so the package
        // cannot serve /workflow under any configuration — exporting a description of it
        // would hand a gateway a route that can never answer.
        Path specPath = emitWithOptions("descriptor_generation",
                BuildOptions.builder().setExportOpenAPI(true).build(), "wf-openapi-noimport");
        Assert.assertFalse(Files.exists(specPath),
                "A package that does not import workflow.management.rest cannot serve the API; "
                        + "the spec must not be exported for it");
    }

    /** Builds the fixture, emits the executable (which runs the lifecycle task), and returns the spec path. */
    private static Path emitWithOptions(String fixture, BuildOptions options, String tempPrefix)
            throws IOException {
        BuildProject project = options == null
                ? BuildProject.load(getEnvironmentBuilder(), RESOURCE_DIRECTORY.resolve(fixture))
                : BuildProject.load(getEnvironmentBuilder(), RESOURCE_DIRECTORY.resolve(fixture), options);
        project.currentPackage().runCodeGenAndModifyPlugins();
        PackageCompilation compilation = project.currentPackage().getCompilation();
        Assert.assertEquals(compilation.diagnosticResult().errorCount(), 0,
                "Compilation errors: " + compilation.diagnosticResult().diagnostics());

        Path specPath = project.targetDir().resolve("openapi")
                .resolve(ManagementApiArtifactExporter.SPEC_FILE_NAME);
        Files.deleteIfExists(specPath);

        // The lifecycle's code-generation-completed tasks run when the backend emits.
        Path execJar = Files.createTempDirectory(tempPrefix).resolve("app.jar");
        JBallerinaBackend.from(compilation, JvmTarget.JAVA_21)
                .emit(JBallerinaBackend.OutputType.EXEC, execJar);
        return specPath;
    }

    // The spec is curated, so this is the drift guard: every resource of the management REST
    // service must appear in the YAML with its verb. A resource added to the service without a
    // matching path here fails the build, which is the moment the author still remembers what
    // the resource does.
    @Test
    public void testEveryServiceResourceIsDescribedInTheSpec() throws IOException {
        Assert.assertTrue(Files.exists(MANAGEMENT_SERVICE_SOURCE),
                "The management service source moved; update this test's path: "
                        + MANAGEMENT_SERVICE_SOURCE);
        String service = Files.readString(MANAGEMENT_SERVICE_SOURCE, StandardCharsets.UTF_8);
        String yaml = embeddedSpec();

        // Any verb, `isolated` optional: a hardcoded verb list is exactly the drift this
        // guard exists to catch — a `delete` resource added tomorrow must fail as
        // "verb absent", not slip past a pattern that never matched it. The path can contain
        // spaces inside `[string workflowId]`, so capture up to the parameter list's opening
        // paren (paths themselves never contain one).
        Pattern resource = Pattern.compile("resource\\s+(?:isolated\\s+)?function\\s+(\\w+)\\s+(.+?)\\(");
        Matcher m = resource.matcher(service);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            String verb = m.group(1);
            String path = toOpenApiPath(m.group(2));
            checked++;
            int at = yaml.indexOf("\n  " + path + ":");
            if (at < 0) {
                missing.add(verb.toUpperCase() + " " + path + " (path absent)");
                continue;
            }
            // The verb must appear inside this path's block — before the next path key.
            int next = yaml.indexOf("\n  /", at + 1);
            String block = next > 0 ? yaml.substring(at, next) : yaml.substring(at);
            if (!block.contains("\n    " + verb + ":")) {
                missing.add(verb.toUpperCase() + " " + path + " (verb absent)");
            }
        }
        Assert.assertTrue(checked >= 30, "The resource scan found implausibly few resources: " + checked);
        Assert.assertEquals(missing, List.of(),
                "workflow-management-openapi.yaml has drifted from the service");
    }

    /** {@code workflows/[string workflowId]/activity\-tree} → {@code /workflows/{workflowId}/activity-tree}. */
    private static String toOpenApiPath(String resourcePath) {
        String path = resourcePath
                .replaceAll("\\[string\\s+(\\w+)\\]", "{$1}")
                .replace("\\-", "-")
                .replace("'", "");
        return "/" + path;
    }

    private static String embeddedSpec() throws IOException {
        try (InputStream in = ManagementApiArtifactExporter.class.getClassLoader()
                .getResourceAsStream(ManagementApiArtifactExporter.SPEC_RESOURCE)) {
            Assert.assertNotNull(in, "The spec must be embedded in the compiler plugin");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ProjectEnvironmentBuilder getEnvironmentBuilder() {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        return ProjectEnvironmentBuilder.getBuilder(environment);
    }
}
