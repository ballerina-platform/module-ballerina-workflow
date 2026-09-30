/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import io.ballerina.lib.workflow.runtime.nativeimpl.DisplayNames;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BString;

/**
 * Reads the runtime's display-name index for the module tests, so the descriptor a test packs can be
 * checked for the names history reads would resolve.
 */
public final class DisplayNameProbe {

    private DisplayNameProbe() {
    }

    public static Object activityLabel(BString activityType) {
        String label = DisplayNames.ofActivity(activityType.getValue()).label();
        return label == null ? null : StringUtils.fromString(label);
    }
}
