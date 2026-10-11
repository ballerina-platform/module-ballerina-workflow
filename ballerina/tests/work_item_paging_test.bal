// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/test;
import ballerina/workflow.management;

@Workflow
function pagedTaskWorkflow(Context ctx, string orderId) returns string|error {
    return ctx->awaitHumanTask("pagedSignoff", {"orderId": orderId}, userRoles = "pager");
}

@Workflow
function otherPagedTaskWorkflow(Context ctx, string orderId) returns string|error {
    return ctx->awaitHumanTask("otherPagedSignoff", {"orderId": orderId}, userRoles = "pager-other");
}

function registerPagedTaskWorkflows() returns error? {
    foreach [function, string] [workflow, name] in [[pagedTaskWorkflow, "pagedTaskWorkflow"],
            [otherPagedTaskWorkflow, "otherPagedTaskWorkflow"]] {
        boolean|error registered = registerWorkflowForTest(workflow, name);
        if registered is error && !registered.message().includes("already registered") {
            return registered;
        }
    }
}

// Every task id `workItems.list` returns across all its pages, following each token to the end.
function listAllWorkItemPages(map<json> params, string role) returns string[]|error {
    string[] seen = [];
    string? token = ();
    foreach int _ in 0 ..< 100 {
        map<json> request = params.clone();
        if token is string {
            request["pageToken"] = token;
        }
        json page = check management:executeCommand({operation: management:LIST_WORK_ITEMS, params: request,
            identity: {userId: "pat", roles: [role]}});
        json[] items = check (check page.items).ensureType();
        int pageLimit = check (params["limit"] ?: 20).ensureType();
        test:assertTrue(items.length() <= pageLimit, "a page never holds more than the limit");
        foreach json item in items {
            string taskId = check (check item.taskId).ensureType();
            seen.push(taskId);
        }
        json next = check page.nextPageToken;
        if next is () {
            return seen;
        }
        test:assertEquals(check page.hasMore, true, "a page with a token reports more");
        token = check next.ensureType();
    }
    return error("workItems.list kept returning tokens after 100 pages");
}

@test:Config {groups: ["unit"]}
function testWorkItemPagesReturnEveryVisibleTaskOnce() returns error? {
    check registerPagedTaskWorkflows();
    string[] mine = [];
    foreach int i in 0 ..< 5 {
        mine.push(check awaitFirstPendingTaskId(check run(pagedTaskWorkflow, "PAGE-" + i.toString())));
    }
    string[] others = [];
    foreach int i in 0 ..< 3 {
        others.push(check awaitFirstPendingTaskId(check run(otherPagedTaskWorkflow, "OTHER-" + i.toString())));
    }

    // Two rows a page: the listing has to resume part-way through server pages and across them.
    string[] seen = check listAllWorkItemPages({kinds: "HUMAN_TASK", status: "PENDING", 'limit: 2}, "pager");
    foreach string taskId in mine {
        test:assertEquals(seen.filter(id => id == taskId).length(), 1, "every visible task is listed exactly once");
    }
    foreach string taskId in others {
        test:assertTrue(seen.indexOf(taskId) is (), "a task for another role is never listed");
    }

    // A filter Temporal cannot query keeps few rows of each server page, so pages come back short until the end.
    string[] narrowed = check listAllWorkItemPages({kinds: "HUMAN_TASK", status: "PENDING", 'limit: 1,
        parentWorkflowType: "otherPagedTaskWorkflow"}, "pager-other");
    test:assertEquals(narrowed.sort(), others.sort());
}
