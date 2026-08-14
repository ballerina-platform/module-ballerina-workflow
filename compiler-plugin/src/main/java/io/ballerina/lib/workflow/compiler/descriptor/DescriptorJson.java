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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON serialization for the Workflow Definition Descriptor: object keys sorted
 * lexicographically, no insignificant whitespace, minimal escaping. Arrays keep the order the
 * builder assembled them in (entity arrays are pre-sorted by {@code name}; schema-internal
 * arrays such as {@code required} keep declaration order — the builder is deterministic, so the
 * serialized form is stable across builds of unchanged source). The content checksum is the
 * SHA-256 of the canonical bytes serialized <em>without</em> the {@code checksum} field.
 *
 * @since 0.9.0
 */
public final class DescriptorJson {

    public static final String CHECKSUM_FIELD = "checksum";

    private DescriptorJson() {
    }

    /**
     * Serializes the document canonically, computes the checksum over the checksum-less form,
     * embeds it as {@code checksum: "sha256:..."} and returns the final canonical bytes.
     *
     * @param document the descriptor document (must not already contain a checksum field)
     * @return the canonical UTF-8 bytes of the document with the checksum embedded
     */
    public static byte[] withChecksum(Map<String, Object> document) {
        Map<String, Object> withoutChecksum = new TreeMap<>(document);
        withoutChecksum.remove(CHECKSUM_FIELD);
        String canonical = serialize(withoutChecksum);
        Map<String, Object> complete = new TreeMap<>(withoutChecksum);
        complete.put(CHECKSUM_FIELD, "sha256:" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)));
        return serialize(complete).getBytes(StandardCharsets.UTF_8);
    }

    /** Serializes a JSON value canonically: sorted object keys, no whitespace. */
    public static String serialize(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":");
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        sb.append('"').append(escape(value.toString())).append('"');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
