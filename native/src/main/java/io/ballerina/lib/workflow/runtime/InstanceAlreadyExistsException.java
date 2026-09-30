/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime;

/**
 * A start was refused because its caller-chosen id is held by an instance the policy did not
 * let it replace or join. Carries the holder's status so the caller can tell a running
 * instance from a closed one.
 *
 * @since 0.11.0
 */
public class InstanceAlreadyExistsException extends RuntimeException {

    private final String instanceId;
    private final String status;

    public InstanceAlreadyExistsException(String instanceId, String status) {
        super("An instance with id '" + instanceId + "' already exists (status: " + status + ")");
        this.instanceId = instanceId;
        this.status = status;
    }

    public String instanceId() {
        return instanceId;
    }

    public String status() {
        return status;
    }
}
