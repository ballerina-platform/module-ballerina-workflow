/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.workflow.compiler;

import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.projects.CodeModifierResult;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Package;
import io.ballerina.projects.Project;
import io.ballerina.projects.directory.BuildProject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static io.ballerina.stdlib.workflow.compiler.util.WorkFlowTestUtil.getEnvironmentBuilder;

/**
 * Test class for workflow compiler plugin modifier.
 *
 * @since 0.1.0
 */
public class WorkflowModifierTest {
    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources",
                    "ballerina-workflow-modifier")
            .toAbsolutePath();

    private Project loadPackage(String path) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(path);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        return project;
    }

    @Test
    public void testWorkflowCodeModifier() {
        Project pro = loadPackage("sample_package_01");
        Package currentPackage = pro.currentPackage();
        CodeModifierResult codeModifierResult = currentPackage.runCodeModifierPlugins();
        Assert.assertEquals(codeModifierResult.reportedDiagnostics().errorCount(), 0);
        Assert.assertEquals(pro.currentPackage().getCompilation().diagnosticResult().errorCount(), 0);

        Path filePath = RESOURCE_DIRECTORY.resolve("sample_package_01/service.bal");
        DocumentId documentId = pro.documentId(filePath);
        SyntaxTree syntaxTree = pro.currentPackage().getDefaultModule().document(documentId).syntaxTree();

        String expected = null;
        try {
            expected = readFile();
        } catch (IOException e) {
            Assert.fail("Failed to read the expected BIR file: typedesc_bir");
        }
        Assert.assertEquals(normalizeLineEndings(syntaxTree.toSourceCode().trim()), normalizeLineEndings(expected));
    }

    private String readFile() throws IOException {
        Path filePath = Path.of("src", "test", "resources",
                "ballerina-workflow-modifier", "sample_package_01", "assert").toAbsolutePath();
        if (Files.exists(filePath)) {
            StringBuilder contentBuilder = new StringBuilder();
            try (Stream<String> stream = Files.lines(filePath, StandardCharsets.UTF_8)) {
                stream.forEach(s -> contentBuilder.append(s).append("\n"));
            }
            return contentBuilder.toString().trim();
        }
        Assert.fail("Expected Assert file not found for workflow modifier test");
        return null;
    }

    private String normalizeLineEndings(String content) {
        return content.replaceAll("\\r\\n?", "\n");
    }
}
