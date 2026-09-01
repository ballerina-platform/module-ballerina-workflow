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
import io.ballerina.projects.Project;
import io.ballerina.projects.plugins.CompilerLifecycleEventContext;
import io.ballerina.projects.plugins.CompilerLifecycleTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Exports the workflow Management REST API's artifacts into a consuming package's build,
 * the way the HTTP compiler plugin exports them for the package's own service declarations.
 *
 * <p>The management service cannot be exported by the stock OpenAPI build extension: that
 * extension reads {@code service ... on listener} declarations of the package being built,
 * while this service is a library-owned {@code service object} attached to a
 * dynamically-managed listener at runtime. Its description therefore ships curated, embedded
 * in this plugin ({@code workflow-management-openapi.yaml} — a compiler-plugin test keeps it
 * complete against the service source), and this task writes it out on demand:
 *
 * <ul>
 *   <li>{@code bal build --export-openapi} — the spec lands in {@code target/openapi/},
 *       beside the specs of the package's own services;</li>
 *   <li>{@code bal build --export-endpoints} — the endpoint metadata (name, port, base path,
 *       spec file) is registered through {@code CompilerLifecycleEventContext
 *       .addEndpointMetadata}, reached reflectively exactly as the HTTP plugin reaches it,
 *       because the API exists only in newer ballerina-lang versions. On a lang without it
 *       the export is silently unavailable, never an error.</li>
 * </ul>
 *
 * <p>Whether the API actually opens its port is a runtime configurable
 * ({@code enableManagementApi}, default {@code false}) that a build cannot see, so the
 * artifacts are exported whenever this plugin runs and an export was requested: they describe
 * the surface the package CAN serve, and the flags are an explicit opt-in.
 *
 * @since 1.0.0
 */
public class ManagementApiArtifactExporter implements CompilerLifecycleTask<CompilerLifecycleEventContext> {

    /** The embedded, curated OpenAPI description of the management REST API. */
    static final String SPEC_RESOURCE = "workflow-management-openapi.yaml";

    /** The file name the spec is exported under, following the http plugin's naming. */
    static final String SPEC_FILE_NAME = "workflow_management_openapi.yaml";

    private static final String OPENAPI_DIR = "openapi";

    // The endpoint metadata contract, mirrored from the http plugin's EndpointYamlGenerator:
    // the surface's name, port, base path, protocol type, and the spec file it is described by.
    private static final String ENDPOINT_NAME = "workflow-management";
    private static final String ENDPOINT_BASE_PATH = "/workflow";
    private static final String ENDPOINT_TYPE = "REST";
    /** The `port` configurable's default in workflow.management.rest. */
    private static final int DEFAULT_PORT = 8234;

    private static final String ENDPOINT_META_INFO_CLASS = "io.ballerina.projects.plugins.EndpointMetaInfo";
    private static final String ADD_ENDPOINT_METADATA_METHOD = "addEndpointMetadata";

    private static final PrintStream ERR = System.err;

    @Override
    public void perform(CompilerLifecycleEventContext context) {
        Project project = context.currentPackage().project();
        BuildOptions options = project.buildOptions();
        if (options.exportOpenAPI()) {
            exportSpec(project);
        }
        if (options.exportEndpoints()) {
            registerEndpointMetadata(context);
        }
    }

    private void exportSpec(Project project) {
        try (InputStream spec = ManagementApiArtifactExporter.class.getClassLoader()
                .getResourceAsStream(SPEC_RESOURCE)) {
            if (spec == null) {
                ERR.println("warning: the workflow management OpenAPI description is missing from "
                        + "the compiler plugin; the spec was not exported");
                return;
            }
            Path openapiDir = project.targetDir().resolve(OPENAPI_DIR);
            Files.createDirectories(openapiDir);
            Files.copy(spec, openapiDir.resolve(SPEC_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // The export is a build convenience: failing the build over it would block the
            // executable a working program needs. Say what happened and move on.
            ERR.println("warning: could not export the workflow management OpenAPI spec: " + e.getMessage());
        }
    }

    private void registerEndpointMetadata(CompilerLifecycleEventContext context) {
        try {
            Class<?> metaInfo = Class.forName(ENDPOINT_META_INFO_CLASS);
            Constructor<?> constructor = metaInfo.getConstructor(String.class, int.class, String.class,
                    String.class, String.class);
            Object endpoint = constructor.newInstance(ENDPOINT_NAME, DEFAULT_PORT, ENDPOINT_BASE_PATH,
                    ENDPOINT_TYPE, SPEC_FILE_NAME);
            Method add = context.getClass().getMethod(ADD_ENDPOINT_METADATA_METHOD, metaInfo);
            add.setAccessible(true);
            add.invoke(context, endpoint);
        } catch (ReflectiveOperationException | SecurityException e) {
            // Endpoint metadata export exists only in newer ballerina-lang versions; on an
            // older one the request simply has nothing to land on.
        }
    }
}
