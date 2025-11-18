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

package io.ballerina.stdlib.workflow.compiler.codemodifier;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.AbstractNodeFactory;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.LiteralValueToken;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TreeModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createEmptyMinutiaeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createIdentifierToken;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createLiteralValueToken;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createSeparatedNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createToken;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createAnnotationNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createBasicLiteralNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createMappingConstructorExpressionNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createMetadataNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createPositionalArgumentNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createQualifiedNameReferenceNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createSpecificFieldNode;
import static io.ballerina.compiler.syntax.tree.SyntaxKind.AT_TOKEN;
import static io.ballerina.compiler.syntax.tree.SyntaxKind.COLON_TOKEN;
import static io.ballerina.stdlib.workflow.compiler.Constants.ACTIVITY;
import static io.ballerina.stdlib.workflow.compiler.Constants.INVOKE_ACTIVITY;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW_ACTIVITIES;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW_INTERNAL;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.isWorkflowModule;

public class WorkflowTreeModifier extends TreeModifier {
    private final SemanticModel semanticModel;
    private final List<NameReferenceNode> activityFunctions;

    public WorkflowTreeModifier(SemanticModel semanticModel) {
        this.semanticModel = semanticModel;
        activityFunctions = new ArrayList<>();
    }

    @Override
    public ServiceDeclarationNode transform(ServiceDeclarationNode serviceDeclarationNode) {
        NodeList<Node> members = this.modifyNodeList(serviceDeclarationNode.members());
        MetadataNode newMetadata = addActivityAnnotation(serviceDeclarationNode.metadata());
        ServiceDeclarationNode.ServiceDeclarationNodeModifier serviceNodeModifier = serviceDeclarationNode.modify();
        return serviceNodeModifier.withMetadata(newMetadata).withMembers(members).apply();
    }

    @Override
    public FunctionCallExpressionNode transform(FunctionCallExpressionNode functionCallNode) {
        if (isActivityFunction(functionCallNode) && functionCallNode.parent().kind() == SyntaxKind.LOCAL_VAR_DECL) {
            NameReferenceNode funcName = functionCallNode.functionName();
            activityFunctions.add(funcName);
            NameReferenceNode newFuncName = NodeFactory.createQualifiedNameReferenceNode(
                    createIdentifierToken(WORKFLOW_INTERNAL),
                    createToken(SyntaxKind.COLON_TOKEN),
                    createIdentifierToken(INVOKE_ACTIVITY)
            );

            SeparatedNodeList<FunctionArgumentNode> argList = functionCallNode.arguments();
            LiteralValueToken valueToken = createLiteralValueToken(SyntaxKind.STRING_LITERAL_TOKEN,
                    "\"" + funcName.toString() + "\"", createEmptyMinutiaeList(), createEmptyMinutiaeList());
            Node functionNameArg = createPositionalArgumentNode(createBasicLiteralNode(SyntaxKind.STRING_LITERAL,
                    valueToken));
            List<Node> newArgs = new ArrayList<>();
            newArgs.add(functionNameArg);
            if (!argList.isEmpty()) {
                newArgs.add(createToken(SyntaxKind.COMMA_TOKEN));
                int size = argList.size();
                for (int i = 0; i < size; i++) {
                    newArgs.add(argList.get(i));
                    if (i < size - 1) {
                        newArgs.add(createToken(SyntaxKind.COMMA_TOKEN));
                    }
                }
            }
            SeparatedNodeList<FunctionArgumentNode> arguments = AbstractNodeFactory.createSeparatedNodeList(newArgs);

            FunctionCallExpressionNode.FunctionCallExpressionNodeModifier funcCallModifier = functionCallNode.modify();
            functionCallNode = funcCallModifier.withFunctionName(newFuncName).withArguments(arguments).apply();
        }
        return functionCallNode;
    }

    private boolean isActivityFunction(FunctionCallExpressionNode functionCallNode) {
        Optional<Symbol> functionSymbolOpt = semanticModel.symbol(functionCallNode);
        if (functionSymbolOpt.isEmpty()) {
            return false;
        }
        Symbol functionSymbol = functionSymbolOpt.get();
        if (functionSymbol.kind() != SymbolKind.FUNCTION) {
            return false;
        }
        FunctionSymbol function = (FunctionSymbol) functionSymbol;
        return function.annotations().stream().anyMatch(annotation -> {
            String annotationName = annotation.getName().orElse("");
            return annotationName.equals(ACTIVITY) && isWorkflowModule(annotation.getModule().orElse(null));
        });
    }

    private MetadataNode addActivityAnnotation(Optional<MetadataNode> metadata) {
        if (activityFunctions.isEmpty()) {
            return metadata.orElse(null);
        }

        List<Node> annotationFields = new ArrayList<>();
        int size = activityFunctions.size();
        for (int i = 0; i < size; i++) {
            NameReferenceNode funcName = activityFunctions.get(i);
            LiteralValueToken valueToken = createLiteralValueToken(SyntaxKind.STRING_LITERAL_TOKEN,
                    "\"" + funcName.toString() + "\"",
                    createEmptyMinutiaeList(), createEmptyMinutiaeList());
            SpecificFieldNode specificFieldNode = createSpecificFieldNode(null,
                    createBasicLiteralNode(SyntaxKind.STRING_LITERAL, valueToken), createToken(COLON_TOKEN), funcName);
            annotationFields.add(specificFieldNode);
            if (i < size - 1) {
                annotationFields.add(createToken(SyntaxKind.COMMA_TOKEN));
            }
        }

        MappingConstructorExpressionNode annotValue = createMappingConstructorExpressionNode(
                createToken(SyntaxKind.OPEN_BRACE_TOKEN), createSeparatedNodeList(annotationFields),
                createToken(SyntaxKind.CLOSE_BRACE_TOKEN));
        AnnotationNode serviceConfigAnnotation = createAnnotationNode(createToken(AT_TOKEN),
                createQualifiedNameReferenceNode(createIdentifierToken(WORKFLOW_INTERNAL), createToken(COLON_TOKEN),
                        createIdentifierToken(WORKFLOW_ACTIVITIES)), annotValue);
        MetadataNode newMetadata;
        if (metadata.isEmpty()) {
            newMetadata = createMetadataNode(null, createNodeList(serviceConfigAnnotation));
        } else {
            NodeList<AnnotationNode> annotations = metadata.get().annotations().add(serviceConfigAnnotation);
            newMetadata = metadata.get().modify().withAnnotations(annotations).apply();
        }
        return newMetadata;
    }
}
