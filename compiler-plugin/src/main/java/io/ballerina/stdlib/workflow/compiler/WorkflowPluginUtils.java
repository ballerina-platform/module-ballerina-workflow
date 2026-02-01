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

package io.ballerina.stdlib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.NodeList;

import java.util.List;
import java.util.Optional;

/**
 * Utility methods shared across workflow compiler plugin components.
 *
 * @since 0.1.0
 */
public final class WorkflowPluginUtils {

    private WorkflowPluginUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks if a module is the ballerina/workflow module.
     *
     * @param moduleSymbol the module symbol to check
     * @return true if it's the workflow module, false otherwise
     */
    public static boolean isWorkflowModule(ModuleSymbol moduleSymbol) {
        if (moduleSymbol == null) {
            return false;
        }
        Optional<String> moduleNameOpt = moduleSymbol.getName();
        if (moduleNameOpt.isEmpty() || !WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get())) {
            return false;
        }
        String orgName = moduleSymbol.id().orgName();
        return WorkflowConstants.PACKAGE_ORG.equals(orgName);
    }

    /**
     * Checks if a function has a specific workflow annotation.
     *
     * @param functionNode   the function definition node
     * @param semanticModel  the semantic model
     * @param annotationName the annotation name to check for
     * @return true if the function has the specified annotation
     */
    public static boolean hasWorkflowAnnotation(FunctionDefinitionNode functionNode, 
                                                 SemanticModel semanticModel,
                                                 String annotationName) {
        Optional<MetadataNode> metadataOpt = functionNode.metadata();
        if (metadataOpt.isEmpty()) {
            return false;
        }

        NodeList<AnnotationNode> annotations = metadataOpt.get().annotations();
        for (AnnotationNode annotation : annotations) {
            if (isWorkflowAnnotation(annotation, semanticModel, annotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if an annotation is a specific workflow annotation.
     *
     * @param annotation     the annotation node
     * @param semanticModel  the semantic model
     * @param expectedName   the expected annotation name
     * @return true if it's the specified workflow annotation
     */
    public static boolean isWorkflowAnnotation(AnnotationNode annotation, SemanticModel semanticModel,
                                                String expectedName) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(annotation);
        if (symbolOpt.isEmpty()) {
            return false;
        }

        Symbol symbol = symbolOpt.get();
        if (symbol.kind() != SymbolKind.ANNOTATION) {
            return false;
        }

        AnnotationSymbol annotationSymbol = (AnnotationSymbol) symbol;
        Optional<String> nameOpt = annotationSymbol.getName();
        if (nameOpt.isEmpty() || !expectedName.equals(nameOpt.get())) {
            return false;
        }

        Optional<ModuleSymbol> moduleOpt = annotationSymbol.getModule();
        return moduleOpt.isPresent() && isWorkflowModule(moduleOpt.get());
    }

    /**
     * Checks if a function symbol has a specific workflow annotation.
     *
     * @param functionSymbol the function symbol
     * @param annotationName the annotation name to check for
     * @return true if the function has the specified annotation
     */
    public static boolean hasWorkflowAnnotation(FunctionSymbol functionSymbol, String annotationName) {
        List<AnnotationSymbol> annotations = functionSymbol.annotations();
        for (AnnotationSymbol annotation : annotations) {
            Optional<String> nameOpt = annotation.getName();
            if (nameOpt.isEmpty()) {
                continue;
            }
            if (annotationName.equals(nameOpt.get())) {
                Optional<ModuleSymbol> moduleOpt = annotation.getModule();
                if (moduleOpt.isPresent() && isWorkflowModule(moduleOpt.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if an expression references a function with a specific workflow annotation.
     *
     * @param expression     the expression node
     * @param semanticModel  the semantic model
     * @param annotationName the annotation name to check for
     * @return true if the expression references a function with the annotation
     */
    public static boolean hasWorkflowAnnotation(ExpressionNode expression, SemanticModel semanticModel,
                                                 String annotationName) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(expression);
        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return false;
        }
        FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
        return hasWorkflowAnnotation(functionSymbol, annotationName);
    }

    /**
     * Checks if the type is workflow:Context from ballerina/workflow module.
     *
     * @param typeSymbol the type symbol to check
     * @return true if it's the workflow Context type
     */
    public static boolean isContextType(TypeSymbol typeSymbol) {
        if (typeSymbol.typeKind() != TypeDescKind.TYPE_REFERENCE) {
            return false;
        }
        
        TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
        Optional<String> nameOpt = typeRef.getName();
        if (nameOpt.isEmpty() || !WorkflowConstants.CONTEXT_TYPE.equals(nameOpt.get())) {
            return false;
        }
        
        Optional<ModuleSymbol> moduleOpt = typeRef.getModule();
        return moduleOpt.isPresent() && isWorkflowModule(moduleOpt.get());
    }

    /**
     * Resolves type references to get the actual underlying type.
     *
     * @param typeSymbol the type symbol which may be a type reference
     * @return the resolved type (unwrapped from type reference if applicable)
     */
    public static TypeSymbol resolveTypeReference(TypeSymbol typeSymbol) {
        if (typeSymbol.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            return ((TypeReferenceTypeSymbol) typeSymbol).typeDescriptor();
        }
        return typeSymbol;
    }

    /**
     * Checks if a type is a subtype of anydata.
     * Uses the semantic model's type checking when available, otherwise falls back to kind checking.
     *
     * @param typeSymbol    the type symbol to check
     * @param semanticModel the semantic model (may be null for fallback behavior)
     * @return true if the type is a subtype of anydata
     */
    public static boolean isSubtypeOfAnydata(TypeSymbol typeSymbol, SemanticModel semanticModel) {
        if (semanticModel != null) {
            return typeSymbol.subtypeOf(semanticModel.types().ANYDATA);
        }
        // Fallback to kind-based checking
        return isSubtypeOfAnydataByKind(typeSymbol);
    }

    /**
     * Checks if a type is a subtype of anydata based on type kind.
     * This is a fallback method when semantic model is not available.
     *
     * @param typeSymbol the type symbol to check
     * @return true if the type is a subtype of anydata
     */
    public static boolean isSubtypeOfAnydataByKind(TypeSymbol typeSymbol) {
        TypeDescKind kind = typeSymbol.typeKind();

        // Handle type references
        if (kind == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            return isSubtypeOfAnydataByKind(typeRef.typeDescriptor());
        }

        // anydata includes: (), boolean, int, float, decimal, string, xml,
        // anydata[], map<anydata>, table<map<anydata>>, record types
        switch (kind) {
            case NIL:
            case BOOLEAN:
            case INT:
            case FLOAT:
            case DECIMAL:
            case STRING:
            case XML:
            case ANYDATA:
            case JSON:
            case BYTE:
            case ARRAY:
            case MAP:
            case RECORD:
            case TABLE:
            case TUPLE:
                return true;
            case UNION:
                // Check if all members are subtypes of anydata
                UnionTypeSymbol unionType = (UnionTypeSymbol) typeSymbol;
                return unionType.memberTypeDescriptors().stream()
                        .allMatch(WorkflowPluginUtils::isSubtypeOfAnydataByKind);
            default:
                return false;
        }
    }

    /**
     * Checks if a type is a subtype of anydata or error.
     *
     * @param typeSymbol    the type symbol to check
     * @param semanticModel the semantic model (may be null for fallback behavior)
     * @return true if the type is a subtype of anydata or error
     */
    public static boolean isSubtypeOfAnydataOrError(TypeSymbol typeSymbol, SemanticModel semanticModel) {
        TypeDescKind kind = typeSymbol.typeKind();

        // Handle type references
        if (kind == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            return isSubtypeOfAnydataOrError(typeRef.typeDescriptor(), semanticModel);
        }

        if (kind == TypeDescKind.ERROR) {
            return true;
        }

        // Handle union types like `string|error`
        if (kind == TypeDescKind.UNION) {
            UnionTypeSymbol unionType = (UnionTypeSymbol) typeSymbol;
            return unionType.memberTypeDescriptors().stream()
                    .allMatch(member -> isSubtypeOfAnydataOrError(member, semanticModel));
        }

        return isSubtypeOfAnydata(typeSymbol, semanticModel);
    }
}
