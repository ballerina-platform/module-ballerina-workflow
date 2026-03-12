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

package io.ballerina.lib.workflow.test;

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;

/**
 * Native implementations for test-only external functions.
 * <p>
 * These functions back dependently-typed {@code @Activity} external functions
 * used in unit tests. Dependently-typed functions require an {@code external}
 * body in Ballerina, so a Java implementation is necessary.
 *
 * @since 0.2.1
 */
public final class TestNatives {

    private TestNatives() {
        // Utility class, prevent instantiation
    }

    /**
     * Dependently-typed activity implementation for tests.
     * Converts the input string to the target type specified by the typedesc.
     *
     * @param data       the input string data
     * @param typedesc   the target type descriptor (from dependent typing)
     * @return the data converted to the target type, or an error
     */
    public static Object convertData(BString data, BTypedesc typedesc) {
        Type targetType = typedesc.getDescribingType();
        return TypesUtil.cloneWithType(data, targetType);
    }
}
