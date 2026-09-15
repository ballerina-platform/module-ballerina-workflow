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

package io.ballerina.lib.workflow.observability;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// The trace an instance owns, derived from its id.
//
// A run's interactions are separate calls, minutes or days apart, in processes that never meet: the start, each
// data event, each decision a person makes, and the execution itself. None of them can hand a trace context to
// the next. They do share the instance id, so the id is what the trace is derived from: everything that names
// the same instance opens its spans in the same trace, without a lookup and without a context to propagate.
//
// The derived ids stand for an anchor span that is never emitted. A tracing UI shows the trace's top spans as
// roots, since the parent they name was never recorded.
final class InstanceTrace {

    // Distinguishes these ids from any other digest of the same instance id.
    private static final String DOMAIN = "ballerina-workflow/instance-trace:";

    private InstanceTrace() {
    }

    // The anchor every span of `instanceId` hangs from, or null when there is no instance to derive from.
    static SpanContext anchorOf(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null;
        }
        byte[] digest = digestOf(instanceId);
        if (digest.length < 24) {
            return null;
        }
        SpanContext anchor = SpanContext.createFromRemoteParent(
                idOf(digest, 0, 16), idOf(digest, 16, 24), TraceFlags.getSampled(), TraceState.getDefault());
        return anchor.isValid() ? anchor : null;
    }

    // The trace an instance's spans share, as the 32 hex characters a tracing UI searches by.
    static String traceIdOf(String instanceId) {
        SpanContext anchor = anchorOf(instanceId);
        return anchor == null ? null : anchor.getTraceId();
    }

    private static byte[] digestOf(String instanceId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((DOMAIN + instanceId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; without it an instance simply has no trace of its own.
            return new byte[0];
        }
    }

    private static String idOf(byte[] digest, int from, int to) {
        String id = HexFormat.of().formatHex(digest, from, to);
        // An all-zero id is the invalid one. No digest realistically lands there, but the contract is cheap to keep.
        return id.chars().anyMatch(c -> c != '0') ? id : id.substring(0, id.length() - 1) + "1";
    }
}
