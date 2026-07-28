/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.context;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowLocal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Makes data-event waits visible: a {@code wait dataEvents.<name>} blocks via {@code Workflow.await()},
 * which by itself leaves no trace in the workflow's event history — so a halted workflow could not show
 * <em>where</em> it is halted. Before a wait blocks (and again when it unblocks) the tracker upserts the
 * current set of awaited event names into the execution's memo under {@link #WAITING_EVENTS_MEMO_KEY}.
 * <p>
 * The upsert is a deterministic, replay-safe workflow command that does two things at once: it appends a
 * {@code WorkflowPropertiesModified} event to the history (which the activity-tree / execution-graph
 * builders turn into a {@code DATA} node with status {@code WAITING}), and it keeps the live wait set
 * readable from a cheap {@code DescribeWorkflowExecution} without fetching history.
 * <p>
 * The wait set lives in a {@link WorkflowLocal}, so it is scoped to the cached workflow instance: it is
 * discarded with the instance on sticky-cache eviction and rebuilt from scratch during replay — on the
 * same worker or any other worker in the cluster — re-issuing the same deterministic upsert commands the
 * history recorded. JVM-global state would survive eviction and make the replayed code skip commands,
 * tripping Temporal's non-determinism detection.
 *
 * @since 1.0.0
 */
public final class WaitingEventsTracker {

    /**
     * Memo key holding the list of data-event names the workflow is currently blocked on.
     */
    public static final String WAITING_EVENTS_MEMO_KEY = "wfWaitingEvents";

    private static final WorkflowLocal<LinkedHashSet<String>> WAITING =
            WorkflowLocal.withCachedInitial(LinkedHashSet::new);

    private WaitingEventsTracker() {
    }

    /**
     * Records that the current workflow is about to block on the given data event and publishes the
     * updated wait set to the execution memo. Must be called on the workflow thread.
     *
     * @param eventName the data event (signal) name about to be awaited
     */
    public static void beginWait(String eventName) {
        LinkedHashSet<String> waiting = WAITING.get();
        if (waiting.add(eventName)) {
            upsert(waiting);
        }
    }

    /**
     * Records that the wait on the given data event has ended (the event arrived, a sibling completed the
     * alternate wait, or the wait was abandoned) and publishes the updated wait set to the execution memo.
     * Must be called on the workflow thread.
     *
     * @param eventName the data event (signal) name that is no longer awaited
     */
    public static void endWait(String eventName) {
        LinkedHashSet<String> waiting = WAITING.get();
        if (!waiting.remove(eventName)) {
            return;
        }
        upsert(waiting);
    }

    private static void upsert(LinkedHashSet<String> waiting) {
        List<String> snapshot = new ArrayList<>(waiting);
        Workflow.upsertMemo(Map.of(WAITING_EVENTS_MEMO_KEY, snapshot));
    }
}
