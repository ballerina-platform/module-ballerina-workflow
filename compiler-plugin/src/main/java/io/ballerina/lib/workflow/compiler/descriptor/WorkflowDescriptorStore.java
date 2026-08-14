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

/**
 * Hand-off point between {@link WorkflowDescriptorBuilder} (which needs a live semantic model,
 * so it runs as a compilation analysis task) and {@link DescriptorPackLifecycleTask} (which
 * needs the generated JAR, so it runs after code generation has completed). The builder runs
 * once per compilation round; the last completed round's document wins — rounds observe the
 * same structural facts, so this is idempotent.
 *
 * @since 0.9.0
 */
public final class WorkflowDescriptorStore {

    private final java.util.concurrent.atomic.AtomicReference<byte[]> descriptorBytes =
            new java.util.concurrent.atomic.AtomicReference<>();

    public void setDescriptorBytes(byte[] bytes) {
        this.descriptorBytes.set(bytes == null ? null : bytes.clone());
    }

    public byte[] descriptorBytes() {
        byte[] bytes = descriptorBytes.get();
        return bytes == null ? null : bytes.clone();
    }

    public boolean isEmpty() {
        byte[] bytes = descriptorBytes.get();
        return bytes == null || bytes.length == 0;
    }
}
