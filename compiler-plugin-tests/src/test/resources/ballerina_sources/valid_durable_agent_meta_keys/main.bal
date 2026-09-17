// Declaration metadata is re-emitted as source for the generated registration, so a key that is
// not a plain identifier has to survive the round trip. Two shapes break it if it does not:
// a field named after a keyword ('wait), and a key written as a string literal with escapes.

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider deskModel = check new ("http://localhost:9099", "test-token");

@workflow:Activity
function fileTicket(string subject) returns string|error {
    return subject;
}

final workflow:DurableAgent specialistAgent = check new ({
    systemPrompt: {role: "Specialist", instructions: "Answer the question."},
    model: deskModel,
    activities: [fileTicket]
});

final workflow:DurableAgent deskAgent = check new ({
    systemPrompt: {role: "Desk", instructions: "Delegate, then summarise."},
    model: deskModel,
    events: {specialistReply: {request: string}},
    peers: [
        {
            agent: specialistAgent,
            description: "Asks the specialist and takes the reply on a channel."
        }
    ],
    // HumanTaskDefinition is open, so these reach the metadata as written. `wait` is a
    // keyword: the declaration quotes it, and emitted bare it would parse as the wait action.
    // Unquoting and re-escaping the string keys would double the escapes.
    humanTasks: {
        signoff: {
            userRoles: "manager",
            'wait: "P1D",
            "label\"with\"quotes": "ok",
            "back\\slash": "ok"
        }
    }
});
