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

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.values.BError;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the Workflow Definition Descriptor ({@code workflow.def.json}) that the workflow
 * compiler plugin packs into the executable JAR at build time. Only ever looks up the one fixed
 * resource name — never scans — so this stays compatible with GraalVM native-image builds where
 * resource access must be to known, registered names. Returns {@code null} when the program was
 * built without a descriptor (e.g. older plugin, or a test run that never produced an executable
 * JAR) — consumers fall back to the registry-assembled metadata.
 *
 * @since 0.9.0
 */
public final class WorkflowDescriptorNative {

    /** The fixed JAR entry name written by the compiler plugin's pack task. */
    public static final String DESCRIPTOR_RESOURCE = "workflow.def.json";

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static Object cachedDescriptor;

    private WorkflowDescriptorNative() {
    }

    /**
     * Returns the packed descriptor as a Ballerina {@code json} value, or {@code null} when no
     * descriptor is packed in the running program. The result is cached: the JAR entry cannot
     * change while the program runs.
     *
     * @return the descriptor document, or {@code null}
     */
    public static Object readPackedDescriptor() {
        if (!loaded) {
            synchronized (LOCK) {
                if (!loaded) {
                    cachedDescriptor = readResource();
                    loaded = true;
                }
            }
        }
        return cachedDescriptor;
    }

    private static Object readResource() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = WorkflowDescriptorNative.class.getClassLoader();
        }
        Object parsed = readFrom(classLoader);
        if (parsed == null && classLoader != WorkflowDescriptorNative.class.getClassLoader()) {
            parsed = readFrom(WorkflowDescriptorNative.class.getClassLoader());
        }
        return parsed;
    }

    private static Object readFrom(ClassLoader classLoader) {
        try (InputStream inputStream = classLoader.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (inputStream == null) {
                return null;
            }
            return JsonUtils.parse(inputStream);
        } catch (IOException | BError e) {
            return null;
        }
    }
}
