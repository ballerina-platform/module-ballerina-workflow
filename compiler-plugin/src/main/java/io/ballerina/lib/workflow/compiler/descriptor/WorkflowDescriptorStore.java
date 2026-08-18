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

package io.ballerina.lib.workflow.compiler.descriptor;

import java.util.Map;

/**
 * Hand-off point between {@link WorkflowDescriptorGenerator} (which needs a live semantic model,
 * so it runs as a code-generation task) and {@link DescriptorPackLifecycleTask} (which needs the
 * generated JAR, so it runs after code generation has completed).
 * <p>
 * The bytes travel through the plugin's <em>user data</em> map rather than a field on this class,
 * because the compiler plugin is instantiated separately for the code-generation and
 * compiler-lifecycle phases: each phase gets its own {@code WorkflowDescriptorStore}, so anything
 * held in a field is invisible to the other side. The user-data map is the same instance across
 * both phases and is the sanctioned channel for exactly this.
 * <p>
 * The builder runs once per compilation round; the last completed round's document wins — rounds
 * observe the same structural facts, so this is idempotent.
 *
 * @since 0.9.0
 */
public final class WorkflowDescriptorStore {

    /** Key under which the descriptor bytes are shared across plugin phases. */
    private static final String USER_DATA_KEY = "workflow.descriptor.bytes";

    private static final byte[] EMPTY = new byte[0];

    private final Map<String, Object> userData;

    public WorkflowDescriptorStore(Map<String, Object> userData) {
        this.userData = userData;
    }

    public void setDescriptorBytes(byte[] bytes) {
        userData.put(USER_DATA_KEY, bytes == null ? EMPTY : bytes.clone());
    }

    /** The stored document, or a zero-length array when this package declares no workflows. */
    public byte[] descriptorBytes() {
        Object shared = userData.get(USER_DATA_KEY);
        return shared instanceof byte[] bytes ? bytes.clone() : EMPTY;
    }

    public boolean isEmpty() {
        return descriptorBytes().length == 0;
    }
}
