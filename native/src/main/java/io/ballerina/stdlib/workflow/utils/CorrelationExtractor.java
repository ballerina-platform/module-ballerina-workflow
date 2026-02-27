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

package io.ballerina.stdlib.workflow.utils;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Utility class for generating workflow IDs.
 * <p>
 * Uses UUID v7 (time-ordered UUID) for workflow ID generation, providing:
 * <ul>
 *   <li>Uniqueness across machines and time</li>
 *   <li>Sortability by creation time</li>
 *   <li>No collisions even in clustered environments</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class CorrelationExtractor {

    private CorrelationExtractor() {
        // Utility class, prevent instantiation
    }

    // SecureRandom for UUID v7 generation
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a workflow ID using UUID v7 (time-ordered UUID).
     * <p>
     * Format: UUID v7 string (e.g., "019c19e6-68f6-7e9c-ba1c-62a6e71f7802")
     *
     * @return workflow ID as a UUID v7 string
     */
    public static String generateWorkflowId() {
        return generateUuidV7();
    }

    /**
     * Generates a UUID v7 (time-ordered UUID) as per RFC 9562.
     * <p>
     * Structure (128 bits):
     * <ul>
     *   <li>48 bits: Unix timestamp in milliseconds</li>
     *   <li>4 bits: Version (7)</li>
     *   <li>12 bits: Random</li>
     *   <li>2 bits: Variant (10)</li>
     *   <li>62 bits: Random</li>
     * </ul>
     *
     * @return UUID v7 string
     */
    private static String generateUuidV7() {
        long timestamp = System.currentTimeMillis();

        // Generate random bits
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        // Build most significant bits: 48-bit timestamp + 4-bit version + 12-bit random
        long msb = (timestamp << 16) | (7L << 12) | ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        // Build least significant bits: 2-bit variant (10) + 62-bit random
        long lsb = ((randomBytes[2] & 0x3FL) | 0x80L) << 56 // variant bits
                | ((randomBytes[3] & 0xFFL) << 48)
                | ((randomBytes[4] & 0xFFL) << 40)
                | ((randomBytes[5] & 0xFFL) << 32)
                | ((randomBytes[6] & 0xFFL) << 24)
                | ((randomBytes[7] & 0xFFL) << 16)
                | ((randomBytes[8] & 0xFFL) << 8)
                | (randomBytes[9] & 0xFFL);

        return new UUID(msb, lsb).toString();
    }

    /**
     * Capitalizes the first letter of a string.
     *
     * @param s the string to capitalize
     * @return the capitalized string
     */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
