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

package io.ballerina.lib.workflow.worker;

import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.concurrent.StrandMetadata;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.values.BFunctionPointer;
import io.ballerina.runtime.internal.values.FPValue;

/**
 * A registered workflow or activity implementation: either a captured function pointer
 * (direct registration through {@code wfInternal:registerWorkflow} — the module's own tests
 * and durable-agent runners) or a symbol reference resolved from the packed workflow
 * descriptor's coordinates (module + function name), invoked through
 * {@link Runtime#callFunction}. Both forms carry the function's type for argument
 * conversion and schema derivation, and both are invoked with a concurrent-safe strand —
 * Temporal worker threads are not Ballerina strands, and a non-concurrent strand would
 * serialize on the global strand lock.
 *
 * @since 0.9.0
 */
public final class WorkflowFunctionRef {

    private final BFunctionPointer pointer;
    private final Module module;
    private final String functionName;
    private final Type functionType;

    private WorkflowFunctionRef(BFunctionPointer pointer, Module module, String functionName,
                                Type functionType) {
        this.pointer = pointer;
        this.module = module;
        this.functionName = functionName;
        this.functionType = functionType;
    }

    /** Wraps a captured function pointer. */
    public static WorkflowFunctionRef of(BFunctionPointer pointer) {
        return new WorkflowFunctionRef(pointer, null, null, pointer.getType());
    }

    /** A symbol reference from descriptor coordinates: invoked by module + function name. */
    public static WorkflowFunctionRef symbolic(Module module, String functionName, Type functionType) {
        return new WorkflowFunctionRef(null, module, functionName, functionType);
    }

    /** The function's type, for parameter/return introspection. */
    public Type getType() {
        return functionType;
    }

    /**
     * Invokes the function with a concurrent-safe strand.
     *
     * @param runtime the Ballerina runtime
     * @param args    positional arguments; trailing omitted defaultable parameters are filled
     *                with their declared defaults by the runtime on both invocation paths
     * @return the function's return value
     */
    public Object call(Runtime runtime, Object... args) {
        if (pointer != null) {
            FPValue fpValue = (FPValue) pointer;
            fpValue.metadata = new StrandMetadata(true, fpValue.metadata.properties());
            return pointer.call(runtime, args);
        }
        return runtime.callFunction(module, functionName, new StrandMetadata(true, null), args);
    }

    /**
     * Whether this and {@code other} resolve the same Ballerina function. Two symbol references
     * built from the same descriptor coordinates are separate objects but the same function —
     * which is what registering one activity from several workflows produces.
     *
     * @param other the reference to compare with
     * @return true when both name the same function
     */
    public boolean refersToSameFunctionAs(WorkflowFunctionRef other) {
        if (other == null) {
            return false;
        }
        if (this == other) {
            return true;
        }
        if (pointer != null && pointer == other.pointer) {
            return true;
        }
        if (functionName != null && functionName.equals(other.functionName)
                && module != null && module.equals(other.module)) {
            return true;
        }
        // Two pointers captured for one function are distinct objects, so fall back to the
        // function's own name — only a genuine name clash should read as a collision.
        String thisName = functionType != null ? functionType.getName() : null;
        String otherName = other.functionType != null ? other.functionType.getName() : null;
        return thisName != null && thisName.equals(otherName);
    }

    @Override
    public String toString() {
        return pointer != null ? "pointer:" + pointer
                : "symbol:" + module + "/" + functionName;
    }
}
