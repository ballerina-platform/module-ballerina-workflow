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
    // `wait` is a keyword, so the declaration quotes it; emitted bare it would parse as the
    // wait action rather than a field.
    peers: [
        {
            agent: specialistAgent,
            name: "askSpecialist",
            description: "Asks the specialist and takes the reply on a channel.",
            'wait: false,
            callbackChannel: "specialistReply"
        }
    ],
    // HumanTaskDefinition is open, so these reach the metadata as written. Unquoting and
    // re-escaping them would double the escapes and break the generated mapping.
    humanTasks: {
        signoff: {
            userRoles: "manager",
            "label\"with\"quotes": "ok",
            "back\\slash": "ok"
        }
    }
});
