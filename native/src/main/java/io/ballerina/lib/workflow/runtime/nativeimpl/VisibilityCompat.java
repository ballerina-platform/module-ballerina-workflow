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

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.filter.v1.StartTimeFilter;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves the management listings on servers without advanced visibility.
 *
 * <p>The embedded dev server (Temporal's {@code TestWorkflowEnvironment}) does not implement
 * {@code ListWorkflowExecutions} — the query-language API every listing here is built on — but it
 * does implement the standard {@code ListOpenWorkflowExecutions} and
 * {@code ListClosedWorkflowExecutions}. This class prefers the query API and falls back to those
 * two the first time the server answers UNIMPLEMENTED, so the same listings work with no server
 * installed at all.
 *
 * <p>Two differences the fallback has to make up for, both measured against the embedded server:
 * its rows carry neither memo nor task queue, so every row is re-read with
 * {@code DescribeWorkflowExecution} (which does carry both) before the caller maps it; and there
 * is no query to filter by, so the caller's filters are applied here instead. Paging is not
 * available either — the fallback reads the whole window (bounded by {@link #MAX_FALLBACK_ROWS})
 * and returns it as one page.
 *
 * @since 1.0.0
 */
final class VisibilityCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityCompat.class);

    // A dev server holds a session's worth of executions; this only guards against a runaway scan.
    private static final int MAX_FALLBACK_ROWS = 1000;
    private static final int FALLBACK_PAGE_SIZE = 100;

    // Latched on the first UNIMPLEMENTED: a server does not grow the API mid-run.
    private static volatile boolean advancedVisibilityUnsupported;

    private VisibilityCompat() {
    }

    /**
     * One page of visibility rows.
     *
     * @param executions    the executions on this page
     * @param nextPageToken the token for the next page, empty when this is the last one
     */
    record Page(List<WorkflowExecutionInfo> executions, ByteString nextPageToken) {
    }

    /**
     * The filters a listing expresses in its query, restated so the fallback can apply them itself.
     * Every field is optional; a null (or empty) field means "do not filter on this".
     */
    static final class Filter {
        private Set<WorkflowExecutionStatus> statuses;
        private String exactType;
        private String typePrefix;
        private String workflowIdPrefix;
        private String taskQueue;
        private Instant startFrom;
        private Instant startTo;
        private Instant closeFrom;
        private Instant closeTo;

        Filter statuses(Set<WorkflowExecutionStatus> value) {
            this.statuses = value;
            return this;
        }

        Filter exactType(String value) {
            this.exactType = value;
            return this;
        }

        Filter typePrefix(String value) {
            this.typePrefix = value;
            return this;
        }

        Filter workflowIdPrefix(String value) {
            this.workflowIdPrefix = value;
            return this;
        }

        Filter taskQueue(Object value) {
            this.taskQueue = asString(value);
            return this;
        }

        Filter startTime(Object from, Object to) {
            this.startFrom = parseInstant(from);
            this.startTo = parseInstant(to);
            return this;
        }

        Filter closeTime(Object from, Object to) {
            this.closeFrom = parseInstant(from);
            this.closeTo = parseInstant(to);
            return this;
        }

        private boolean matches(WorkflowExecutionInfo info) {
            if (statuses != null && !statuses.contains(info.getStatus())) {
                return false;
            }
            String type = info.getType().getName();
            if (exactType != null && !exactType.equals(type)) {
                return false;
            }
            if (typePrefix != null && !type.startsWith(typePrefix)) {
                return false;
            }
            if (workflowIdPrefix != null && !info.getExecution().getWorkflowId().startsWith(workflowIdPrefix)) {
                return false;
            }
            if (taskQueue != null && !taskQueue.equals(info.getTaskQueue())) {
                return false;
            }
            return inRange(info.getStartTime(), startFrom, startTo) && inRange(info.getCloseTime(), closeFrom, closeTo);
        }

        // An unset close time (a running execution) is absent rather than zero: a close-time
        // filter excludes it, the same way a CloseTime clause does server-side.
        private static boolean inRange(Timestamp value, Instant from, Instant to) {
            if (from == null && to == null) {
                return true;
            }
            if (value.getSeconds() == 0 && value.getNanos() == 0) {
                return false;
            }
            Instant at = Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
            return (from == null || !at.isBefore(from)) && (to == null || !at.isAfter(to));
        }

        // Open executions are the only ones the open listing can return, and vice versa, so the
        // fallback skips a call it knows cannot match.
        private boolean wantsOpen() {
            return statuses == null || statuses.contains(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        }

        private boolean wantsClosed() {
            return statuses == null || statuses.stream()
                    .anyMatch(s -> s != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        }
    }

    /**
     * Reads one page of executions: through the query API where the server has it, through the
     * standard listings where it does not.
     *
     * @param client          the workflow client
     * @param query           the visibility query, used only on the query API path
     * @param filter          the same filters in structured form, applied by the fallback
     * @param pageSize        rows per page on the query API path
     * @param pageToken       the page to read, or {@link ByteString#EMPTY} for the first
     * @param deadlineSeconds per-RPC deadline
     * @return the page; its token is empty on the fallback path, which answers in one page
     */
    static Page fetchPage(WorkflowClient client, String query, Filter filter, int pageSize,
                          ByteString pageToken, long deadlineSeconds) {
        if (!advancedVisibilityUnsupported) {
            try {
                ListWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                        .listWorkflowExecutions(ListWorkflowExecutionsRequest.newBuilder()
                                .setNamespace(client.getOptions().getNamespace())
                                .setQuery(query)
                                .setPageSize(pageSize)
                                .setNextPageToken(pageToken)
                                .build());
                return new Page(response.getExecutionsList(), response.getNextPageToken());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.UNIMPLEMENTED) {
                    throw e;
                }
                advancedVisibilityUnsupported = true;
                LOGGER.info("This workflow server does not implement ListWorkflowExecutions; serving listings "
                        + "from the standard open/closed listings instead. Filtering and paging are applied "
                        + "in the client, and results are capped at {} rows.", MAX_FALLBACK_ROWS);
            }
        }
        // The fallback answers the whole window at once, so a request for a later page has nothing
        // left to return.
        if (!pageToken.isEmpty()) {
            return new Page(List.of(), ByteString.EMPTY);
        }
        return new Page(listWithoutQuery(client, filter, deadlineSeconds), ByteString.EMPTY);
    }

    /** Whether the server has already answered UNIMPLEMENTED for the query API this run. */
    static boolean advancedVisibilityUnsupported() {
        return advancedVisibilityUnsupported;
    }

    private static List<WorkflowExecutionInfo> listWithoutQuery(WorkflowClient client, Filter filter,
                                                                long deadlineSeconds) {
        // The standard listings require a start-time window; an unbounded one is the whole history
        // the dev server holds.
        StartTimeFilter window = StartTimeFilter.newBuilder()
                .setEarliestTime(toTimestamp(filter.startFrom, Instant.EPOCH))
                .setLatestTime(toTimestamp(filter.startTo, Instant.now().plusSeconds(3600)))
                .build();
        String namespace = client.getOptions().getNamespace();
        List<WorkflowExecutionInfo> rows = new ArrayList<>();
        if (filter.wantsOpen()) {
            ByteString token = ByteString.EMPTY;
            do {
                ListOpenWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                        .listOpenWorkflowExecutions(ListOpenWorkflowExecutionsRequest.newBuilder()
                                .setNamespace(namespace)
                                .setMaximumPageSize(FALLBACK_PAGE_SIZE)
                                .setNextPageToken(token)
                                .setStartTimeFilter(window)
                                .build());
                rows.addAll(response.getExecutionsList());
                token = response.getNextPageToken();
            } while (!token.isEmpty() && rows.size() < MAX_FALLBACK_ROWS);
        }
        if (filter.wantsClosed()) {
            ByteString token = ByteString.EMPTY;
            do {
                ListClosedWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                        .listClosedWorkflowExecutions(ListClosedWorkflowExecutionsRequest.newBuilder()
                                .setNamespace(namespace)
                                .setMaximumPageSize(FALLBACK_PAGE_SIZE)
                                .setNextPageToken(token)
                                .setStartTimeFilter(window)
                                .build());
                rows.addAll(response.getExecutionsList());
                token = response.getNextPageToken();
            } while (!token.isEmpty() && rows.size() < MAX_FALLBACK_ROWS);
        }

        List<WorkflowExecutionInfo> matched = new ArrayList<>();
        for (WorkflowExecutionInfo row : rows) {
            WorkflowExecutionInfo enriched = describe(client, row, deadlineSeconds);
            if (filter.matches(enriched)) {
                matched.add(enriched);
            }
        }
        return matched;
    }

    // The standard listings return neither memo nor task queue, which every caller reads off the
    // row; a describe per row restores them. One RPC per row is affordable only because this path
    // exists for a dev server holding a session's worth of executions.
    private static WorkflowExecutionInfo describe(WorkflowClient client, WorkflowExecutionInfo row,
                                                  long deadlineSeconds) {
        try {
            WorkflowExecution execution = WorkflowExecution.newBuilder()
                    .setWorkflowId(row.getExecution().getWorkflowId())
                    .setRunId(row.getExecution().getRunId())
                    .build();
            return client.getWorkflowServiceStubs()
                    .blockingStub()
                    .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                    .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(execution)
                            .build())
                    .getWorkflowExecutionInfo();
        } catch (StatusRuntimeException e) {
            // A row that vanished between listing and describing is simply reported as listed.
            LOGGER.debug("Could not describe '{}' while listing; using the listing row as-is",
                    row.getExecution().getWorkflowId(), e);
            return row;
        }
    }

    private static Timestamp toTimestamp(Instant value, Instant fallback) {
        Instant at = value != null ? value : fallback;
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }

    private static Instant parseInstant(Object value) {
        String text = asString(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            // The query path lets the server reject a malformed bound; here it simply does not bind.
            return null;
        }
    }

    private static String asString(Object value) {
        if (value instanceof io.ballerina.runtime.api.values.BString text && !text.getValue().isBlank()) {
            return text.getValue();
        }
        return null;
    }
}
