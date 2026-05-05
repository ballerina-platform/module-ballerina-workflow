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

package io.ballerina.lib.workflow.activity;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;

/**
 * Native bridges for dependently-typed builtin activity functions in the
 * {@code ballerina/workflow.activity} submodule.
 * <p>
 * Dependently-typed Ballerina functions (those using {@code typedesc<...> t = <>}
 * with return type {@code t|error}) require an {@code external} body. These
 * methods simply forward the call — including the inferred typedesc — to an
 * internal Ballerina dispatcher function in the same module. The dispatcher
 * does the real work (HTTP method match-case, data binding via the underlying
 * client) and benefits from the typedesc inference.
 *
 * @since 0.4.0
 */
public final class BuiltinActivities {

    private BuiltinActivities() {
        // Utility class, prevent instantiation
    }

    /**
     * External entry point for {@code activity:callRestAPI}.
     * Delegates to the {@code callRestAPIDispatch} Ballerina function defined
     * in the same module, forwarding all parameters including the inferred
     * {@code typedesc} so the underlying {@code http:Client} performs payload
     * data binding into the caller's expected type.
     */
    public static Object callRestAPI(Environment env, BObject connection, BString method,
                                     BString path, Object message, Object headers,
                                     BTypedesc t) {
        return env.getRuntime().callFunction(env.getCurrentModule(), "callRestAPIDispatch",
                null, connection, method, path, message, headers, t);
    }
}
