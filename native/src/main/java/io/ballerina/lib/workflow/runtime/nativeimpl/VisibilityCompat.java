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
import java.util.function.Predicate;

/**
 * Serves the management listings on servers without advanced visibility.
 *
 * <p>The embedded dev server (Temporal's {@code TestWorkflowEnvironment}) does not implement
 * {@code ListWorkflowExecutions} — the query-language API every listing here is built on — but it
 * does implement the standard {@code ListOpenWorkflowExecutions} and
 * {@code ListClosedWorkflowExecutions}. This class prefers the query API and falls back to those
 * two the first time the server answers UNIMPLEMENTED.
 *
 * <p>Two differences the fallback makes up for: the standard listings take no query, so the
 * caller's filters are applied here; and their rows carry neither memo nor task queue, so a row
 * that survives the filters is re-read with {@code DescribeWorkflowExecution} before the caller
 * maps it. Everything the row itself carries — type, status, workflow ID, times — is tested first,
 * so a describe is only spent on a row that is going to be returned.
 *
 * @since 1.0.0
 */
public final class VisibilityCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityCompat.class);

    private static final int FETCH_PAGE_SIZE = 100;

    // What the current connection answered when the query API was last tried. Latched on
    // UNIMPLEMENTED — a server does not grow the API mid-run — and cleared when a worker starts,
    // since the next one may be talking to a different server.
    private static volatile boolean advancedVisibilityUnsupported;

    private VisibilityCompat() {
    }

    /** Forgets what was measured about the previous connection. Called when a worker initialises. */
    public static void reset() {
        advancedVisibilityUnsupported = false;
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
     * Every field is optional; an unset field means "do not filter on this".
     *
     * <p>Time bounds are held as the caller wrote them and parsed only if the fallback runs: the
     * query path hands them to the server, whose datetime grammar is wider than {@code
     * Instant.parse} (it takes Unix nanoseconds, for one), and this must not narrow it.
     */
    static final class Filter {
        private Set<WorkflowExecutionStatus> statuses;
        private Predicate<String> typeTest;
        private String workflowIdPrefix;
        private String taskQueue;
        private String startFrom;
        private String startTo;
        private String closeFrom;
        private String closeTo;

        Filter statuses(Set<WorkflowExecutionStatus> value) {
            this.statuses = value;
            return this;
        }

        /** The type test the call site already applies to the rows it keeps. */
        Filter typeTest(Predicate<String> value) {
            this.typeTest = value;
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
            this.startFrom = asString(from);
            this.startTo = asString(to);
            return this;
        }

        Filter closeTime(Object from, Object to) {
            this.closeFrom = asString(from);
            this.closeTo = asString(to);
            return this;
        }

        // Everything the standard listing row carries, so a row that fails here costs no describe.
        private boolean matchesRow(WorkflowExecutionInfo info) {
            if (statuses != null && !statuses.contains(info.getStatus())) {
                return false;
            }
            if (typeTest != null && !typeTest.test(info.getType().getName())) {
                return false;
            }
            if (workflowIdPrefix != null && !info.getExecution().getWorkflowId().startsWith(workflowIdPrefix)) {
                return false;
            }
            return inRange(info.getStartTime(), parse(startFrom), parse(startTo))
                    && inRange(info.getCloseTime(), parse(closeFrom), parse(closeTo));
        }

        // The task queue is absent from a standard listing row, so this waits for the describe.
        private boolean matchesDescribed(WorkflowExecutionInfo info) {
            return taskQueue == null || taskQueue.equals(info.getTaskQueue());
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

        private Instant windowStart() {
            Instant from = parse(startFrom);
            return from != null ? from : Instant.EPOCH;
        }

        private Instant windowEnd() {
            Instant to = parse(startTo);
            return to != null ? to : Instant.now().plusSeconds(3600);
        }
    }

    /**
     * Reads one page of executions: through the query API where the server has it, through the
     * standard listings where it does not.
     *
     * @param client          the workflow client
     * @param query           the visibility query, used only on the query API path
     * @param filter          the same filters in structured form, applied by the fallback
     * @param pageSize        rows per page; a value below 1 asks for everything that matches
     * @param pageToken       the page to read, or {@link ByteString#EMPTY} for the first
     * @param deadlineSeconds per-RPC deadline
     * @return the page, and the token to read the next one with
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
                        + "from the standard open/closed listings instead, with filtering and paging applied "
                        + "in the client.");
            }
        }
        return listWithoutQuery(client, filter, pageSize, offsetOf(pageToken), deadlineSeconds);
    }

    // The fallback's own continuation token: an offset into the same filtered listing. It is never
    // mixed with the query API's opaque token — the path is chosen once per connection and latched.
    private static ByteString tokenFor(int offset) {
        return ByteString.copyFromUtf8(Integer.toString(offset));
    }

    private static int offsetOf(ByteString pageToken) {
        if (pageToken.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(pageToken.toStringUtf8()));
        } catch (NumberFormatException e) {
            // A token from the query API, or a malformed one: start from the beginning rather
            // than failing the listing.
            return 0;
        }
    }

    private static Page listWithoutQuery(WorkflowClient client, Filter filter, int pageSize, int offset,
                                         long deadlineSeconds) {
        // Filtered on what the rows themselves carry, before a single describe is spent.
        List<WorkflowExecutionInfo> candidates = new ArrayList<>();
        for (WorkflowExecutionInfo row : fetchRows(client, filter, deadlineSeconds)) {
            if (filter.matchesRow(row)) {
                candidates.add(row);
            }
        }

        List<WorkflowExecutionInfo> page = new ArrayList<>();
        int cursor = offset;
        while (cursor < candidates.size() && (pageSize < 1 || page.size() < pageSize)) {
            WorkflowExecutionInfo described = describe(client, candidates.get(cursor), deadlineSeconds);
            cursor++;
            if (filter.matchesDescribed(described)) {
                page.add(described);
            }
        }
        // A caller that pages must be told when rows remain, or a truncated listing reads as a
        // complete one. The token can outlive its rows — the last candidates may yet fail the
        // describe-level filter — so a page can come back empty; that is the honest answer.
        return new Page(page, cursor < candidates.size() ? tokenFor(cursor) : ByteString.EMPTY);
    }

    private static List<WorkflowExecutionInfo> fetchRows(WorkflowClient client, Filter filter,
                                                         long deadlineSeconds) {
        // The standard listings require a start-time window; an unbounded one is the whole history
        // the server holds.
        StartTimeFilter window = StartTimeFilter.newBuilder()
                .setEarliestTime(toTimestamp(filter.windowStart()))
                .setLatestTime(toTimestamp(filter.windowEnd()))
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
                                .setMaximumPageSize(FETCH_PAGE_SIZE)
                                .setNextPageToken(token)
                                .setStartTimeFilter(window)
                                .build());
                rows.addAll(response.getExecutionsList());
                token = response.getNextPageToken();
            } while (!token.isEmpty());
        }
        if (filter.wantsClosed()) {
            ByteString token = ByteString.EMPTY;
            do {
                ListClosedWorkflowExecutionsResponse response = client.getWorkflowServiceStubs()
                        .blockingStub()
                        .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                        .listClosedWorkflowExecutions(ListClosedWorkflowExecutionsRequest.newBuilder()
                                .setNamespace(namespace)
                                .setMaximumPageSize(FETCH_PAGE_SIZE)
                                .setNextPageToken(token)
                                .setStartTimeFilter(window)
                                .build());
                rows.addAll(response.getExecutionsList());
                token = response.getNextPageToken();
            } while (!token.isEmpty());
        }
        return rows;
    }

    // The standard listings return neither memo nor task queue, which every caller reads off the
    // row; a describe restores them.
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
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                // Anything else — a deadline, an unavailable server — would otherwise be served as
                // a row missing its memo, and a human task summary without its task name reads as
                // data rather than as the failure it is.
                throw e;
            }
            // A row that vanished between listing and describing is simply reported as listed.
            LOGGER.debug("Could not describe '{}' while listing; using the listing row as-is",
                    row.getExecution().getWorkflowId(), e);
            return row;
        }
    }

    private static Timestamp toTimestamp(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }

    private static Instant parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            // Dropping the bound would answer a filtered request with an unfiltered listing — more
            // rows than were asked for, reported as success. The listing entry points turn this
            // into a management error. Only the fallback path reaches here, so the query API's
            // wider grammar is left to the server.
            throw new IllegalArgumentException("Invalid timestamp '" + text + "': expected ISO-8601", e);
        }
    }

    private static String asString(Object value) {
        if (value instanceof io.ballerina.runtime.api.values.BString text && !text.getValue().isBlank()) {
            return text.getValue();
        }
        return null;
    }
}
