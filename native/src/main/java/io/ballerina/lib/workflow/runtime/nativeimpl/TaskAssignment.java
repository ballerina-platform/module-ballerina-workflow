/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.lib.workflow.TaskKeys;
import io.ballerina.runtime.api.values.BArray;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who may act on a task, as its memo records it, and the one rule every completion path applies.
 *
 * @param userRoles     roles that may act
 * @param users         user ids that may act
 * @param excludedUsers user ids that may not act, whatever their roles
 * @param excludedRoles roles that may not act
 */
public record TaskAssignment(List<String> userRoles, List<String> users, List<String> excludedUsers,
                             List<String> excludedRoles) {

    // Throws when userRoles is present but undecodable: a completion must not proceed on an unreadable audience.
    public static TaskAssignment fromMemo(DataConverter dc, Map<String, Payload> memo) {
        return new TaskAssignment(strings(dc, memo, TaskKeys.USER_ROLES), strings(dc, memo, TaskKeys.USERS),
                strings(dc, memo, TaskKeys.EXCLUDED_USERS), strings(dc, memo, TaskKeys.EXCLUDED_ROLES));
    }

    public static List<String> roles(BArray callerRoles) {
        if (callerRoles == null) {
            return null;
        }
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < callerRoles.size(); i++) {
            roles.add(String.valueOf(callerRoles.get(i)));
        }
        return roles;
    }

    /**
     * Why the caller may not act on this task, or null when they may. A caller without roles is an internal
     * path and is not checked, as before; with roles, a task that names users needs a user id to decide.
     *
     * @param callerRoles the caller's roles, or null when no identity was supplied
     * @param userId      the caller's user id, or null
     * @param subject     how to name the task in the message
     * @return the denial message, or null
     */
    public String denial(List<String> callerRoles, String userId, String subject) {
        if (callerRoles == null) {
            return null;
        }
        if (callerRoles.stream().anyMatch(excludedRoles::contains)) {
            return "Unauthorized: caller holds a role excluded from " + subject;
        }
        if (!excludedUsers.isEmpty()) {
            if (userId == null) {
                return "Unauthorized: " + subject + " excludes users and the caller has no user id";
            }
            if (excludedUsers.contains(userId)) {
                return "Unauthorized: caller is excluded from " + subject;
            }
        }
        if (userRoles.isEmpty() && users.isEmpty()) {
            return null;
        }
        if (callerRoles.stream().anyMatch(userRoles::contains)) {
            return null;
        }
        if (userId != null && users.contains(userId)) {
            return null;
        }
        if (userRoles.isEmpty() && userId == null) {
            return "Unauthorized: " + subject + " is assigned to users and the caller has no user id";
        }
        return "Unauthorized: caller does not have a required role to complete " + subject
                + ". Required one of: " + userRoles + (users.isEmpty() ? "" : " or user in " + users);
    }

    private static List<String> strings(DataConverter dc, Map<String, Payload> memo, String key) {
        Payload payload = memo.get(key);
        if (payload == null) {
            return List.of();
        }
        String[] values = dc.fromPayload(payload, String[].class, String[].class);
        return values == null ? List.of() : List.of(values);
    }
}
