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

import io.ballerina.compiler.api.ModuleID;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.AbstractNodeFactory;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.BaseNodeModifier;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.LiteralValueToken;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeParser;
import io.ballerina.compiler.syntax.tree.ReturnTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TypeCastParamNode;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleDescriptor;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.ModuleName;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDependencyScope;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.projects.PackageName;
import io.ballerina.projects.PackageOrg;
import io.ballerina.projects.PackageVersion;
import io.ballerina.projects.Project;
import io.ballerina.projects.ResolvedPackageDependency;
import io.ballerina.tools.diagnostics.Location;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLocation;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
import static io.ballerina.compiler.syntax.tree.SyntaxKind.GT_TOKEN;
import static io.ballerina.stdlib.workflow.compiler.Constants.ACTIVITY;
import static io.ballerina.stdlib.workflow.compiler.Constants.INVOKE_ACTIVITY;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW_ACTIVITIES;
import static io.ballerina.stdlib.workflow.compiler.Constants.WORKFLOW_INTERNAL;
import static io.ballerina.stdlib.workflow.compiler.WorkflowCompilerPluginUtil.isWorkflowModule;

public class WorkflowTreeModifier extends BaseNodeModifier {
    private final Package currentPackage;
    private final SemanticModel semanticModel;
    private final List<NameReferenceNode> activityFunctions;

    public WorkflowTreeModifier(SemanticModel semanticModel, Package currentPackage) {
        this.currentPackage = currentPackage;
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
    public ExpressionNode transform(FunctionCallExpressionNode functionCallNode) {
        FunctionSymbol activityFunctionSym = getActivityFunctionSymbol(functionCallNode);
        if (activityFunctionSym != null && functionCallNode.parent().kind() == SyntaxKind.LOCAL_VAR_DECL) {
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

            // Cast the result of the internal invoke activity function to the original activity function return type
            Node retTypeDescNode = getReturnTypeDescNode(activityFunctionSym, functionCallNode);
            if (retTypeDescNode != null) {
                TypeCastParamNode typeCastParamNode = NodeFactory.createTypeCastParamNode(
                        NodeFactory.createEmptyNodeList(), retTypeDescNode);
                return NodeFactory.createTypeCastExpressionNode(createToken(SyntaxKind.LT_TOKEN), typeCastParamNode,
                        createToken(GT_TOKEN), functionCallNode);
            }
        }
        return functionCallNode;
    }

    private Node getReturnTypeDescNode(FunctionSymbol activityFunctionSym, FunctionCallExpressionNode funCallNode) {
        Location activityFuncLocation = activityFunctionSym.getLocation().orElse(null);
        ModuleSymbol moduleSymbol = activityFunctionSym.getModule().orElse(null);
        if (moduleSymbol == null || activityFuncLocation == null) {
            return null;
        }

        ModuleID moduleID = moduleSymbol.id();
        PackageOrg packageOrg = PackageOrg.from(moduleID.orgName());
        PackageName packageName = PackageName.from(moduleID.packageName());
        PackageVersion packageVersion = PackageVersion.from(moduleID.version());
        PackageDescriptor packageDescriptor = PackageDescriptor.from(packageOrg, packageName, packageVersion);
        ResolvedPackageDependency rPD = new ResolvedPackageDependency(this.currentPackage,
                PackageDependencyScope.DEFAULT);
        List<Package> packageList = new ArrayList<>(this.currentPackage.getResolution().dependencyGraph()
                .getDirectDependencies(rPD).stream().map(ResolvedPackageDependency::packageInstance).toList());
        packageList.add(currentPackage);
        Optional<Package> optTargetPkg = packageList.stream().filter(
                pkg -> pkg.descriptor().equals(packageDescriptor)).findFirst();
        if (optTargetPkg.isEmpty()) {
            return null;
        }

        Package targetPkg = optTargetPkg.get();
        Project project = targetPkg.project();
        String fileName = activityFuncLocation.lineRange().fileName();
        String filePath = getFilePath(project, moduleID.moduleName(), fileName);
        Module targtModule = Optional.ofNullable(targetPkg.module(ModuleName.from(packageName, moduleID.moduleName().substring(moduleID.moduleName().lastIndexOf(".") + 1))))
                .orElseGet(() -> targetPkg.module(ModuleName.from(packageName)));
        Document document = targtModule.document(project.documentId(project.sourceRoot().resolve(filePath)));
        Optional<FunctionDefinitionNode> functionSyntaxNode = ((ModulePartNode) document.syntaxTree().rootNode())
                .members().stream().filter(member ->
                        member.kind() == SyntaxKind.FUNCTION_DEFINITION &&
                                ((FunctionDefinitionNode) member).functionName().text().equals(
                                        activityFunctionSym.getName().orElse("")))
                .map(member -> (FunctionDefinitionNode) member).findFirst();
        if (functionSyntaxNode.isEmpty()) {
            return null;
        }

        Optional<ReturnTypeDescriptorNode> retTypeNode = functionSyntaxNode.get().functionSignature().returnTypeDesc();
        if (retTypeNode.isEmpty()) {
            return NodeFactory.createNilTypeDescriptorNode(createToken(SyntaxKind.OPEN_PAREN_TOKEN),
                    createToken(SyntaxKind.CLOSE_PAREN_TOKEN));
        }
        Node retType = retTypeNode.get().type();
        modifyTypeWithModulePrefix(retType, funCallNode);
        return retType;
    }

    private void modifyTypeWithModulePrefix(Node type, FunctionCallExpressionNode funCallNode) {
        ((ModulePartNode) funCallNode.syntaxTree().rootNode()).imports().stream().filter(importDeclarationNode -> {
            importDeclarationNode.moduleName()
        })
    }

    private String getFilePath(Project project, String moduleName, String fileName) {
        if (project.sourceRoot().toString().endsWith(moduleName)) {
            return fileName;
        }
        int lastDot = moduleName.lastIndexOf('.');
        return "modules/" + moduleName.substring(lastDot + 1) + "/" + fileName;
    }

    /**
     * Get the function symbol if the function is an activity function.
     *
     * @param functionCallNode Function call expression node
     * @return FunctionSymbol if the function is an activity function, else null
     */
    private FunctionSymbol getActivityFunctionSymbol(FunctionCallExpressionNode functionCallNode) {
        Optional<Symbol> functionSymbolOpt = semanticModel.symbol(functionCallNode);
        if (functionSymbolOpt.isEmpty()) {
            return null;
        }
        Symbol functionSymbol = functionSymbolOpt.get();
        if (functionSymbol.kind() != SymbolKind.FUNCTION) {
            return null;
        }
        FunctionSymbol function = (FunctionSymbol) functionSymbol;
        return function.annotations().stream().anyMatch(annotation -> {
            String annotationName = annotation.getName().orElse("");
            return annotationName.equals(ACTIVITY) && isWorkflowModule(annotation.getModule().orElse(null));
        })? function : null;
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
