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

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BString;

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

    /** The fixed classpath entry of the packed descriptor (a generated package resource). */
    public static final String DESCRIPTOR_RESOURCE = "workflow.def.json";

    /** Physical package resources land under this prefix; probed as a fallback. */
    private static final String RESOURCES_PREFIX = "resources/";

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

    /**
     * Registers the workflow descriptor handed over by the compiler plugin's generated code —
     * the document embedded as data in {@code __registerWorkflowsAndStart}. Generated sources
     * travel through every compilation mode (build, run, and test), so this is the runtime's
     * primary descriptor source; the packed classpath resource backs external tooling and
     * programs built by the same plugin (byte-identical content). The binding of
     * {@code wfInternal:registerWorkflowDescriptor}.
     *
     * @param descriptorJson the canonical descriptor document
     * @return {@code true} on success, or a Ballerina error when the document is not valid JSON
     */
    public static Object registerWorkflowDescriptor(BString descriptorJson) {
        try {
            Object parsed = JsonUtils.parse(descriptorJson.getValue());
            synchronized (LOCK) {
                cachedDescriptor = parsed;
                loaded = true;
            }
            return true;
        } catch (BError e) {
            return ErrorCreator.createError(StringUtils.fromString(
                    "Invalid workflow descriptor document: " + e.getMessage()));
        }
    }

    /**
     * Test seam: installs a descriptor document as if it had been read from the packed
     * resource. {@code bal test} runs of this module have neither an executable JAR nor
     * plugin-generated registration, so the module's own tests inject the document this way;
     * passing {@code null} restores the not-packed state.
     *
     * @param descriptor the descriptor document, or {@code null} to clear
     */
    public static void setPackedDescriptorForTesting(Object descriptor) {
        synchronized (LOCK) {
            cachedDescriptor = descriptor;
            loaded = true;
        }
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
        Object parsed = readEntry(classLoader, DESCRIPTOR_RESOURCE);
        if (parsed == null) {
            parsed = readEntry(classLoader, RESOURCES_PREFIX + DESCRIPTOR_RESOURCE);
        }
        return parsed;
    }

    private static Object readEntry(ClassLoader classLoader, String resourcePath) {
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return null;
            }
            return JsonUtils.parse(inputStream);
        } catch (IOException | BError e) {
            return null;
        }
    }
}
