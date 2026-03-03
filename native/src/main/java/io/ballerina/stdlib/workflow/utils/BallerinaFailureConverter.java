/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.FailureConverter;
import io.temporal.failure.DefaultFailureConverter;

/**
 * Custom {@link FailureConverter} that replaces the default "JavaSDK" source
 * with "BallerinaSDK" and strips Java stack traces from all failures
 * (including nested causes) so the Temporal UI reflects the Ballerina runtime
 * instead of exposing Java SDK internals.
 * <p>
 * Delegates all conversion logic to the {@link DefaultFailureConverter} and
 * post-processes the resulting {@link Failure} proto.
 *
 * @since 0.1.0
 */
public final class BallerinaFailureConverter implements FailureConverter {

    private final DefaultFailureConverter delegate = new DefaultFailureConverter();

    @Override
    public RuntimeException failureToException(Failure failure, DataConverter dataConverter) {
        return delegate.failureToException(failure, dataConverter);
    }

    @Override
    public Failure exceptionToFailure(Throwable throwable, DataConverter dataConverter) {
        Failure failure = delegate.exceptionToFailure(throwable, dataConverter);
        // Replace source and strip stack traces throughout the failure chain
        return sanitizeFailure(failure);
    }

    /**
     * Recursively sanitises a {@link Failure} proto tree: replaces the
     * {@code source} with "BallerinaSDK" and clears the {@code stackTrace}
     * field on every node so that Java internals are never exposed.
     */
    private Failure sanitizeFailure(Failure failure) {
        Failure.Builder builder = failure.toBuilder()
                .clearSource()
                .setStackTrace("");
        if (failure.hasCause()) {
            builder.setCause(sanitizeFailure(failure.getCause()));
        }
        return builder.build();
    }
}
