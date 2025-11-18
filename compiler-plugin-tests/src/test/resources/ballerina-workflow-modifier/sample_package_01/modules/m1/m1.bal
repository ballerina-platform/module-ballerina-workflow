import ballerina/workflow;

@workflow:Activity
public isolated function performActivity(int a, string name) returns int {
    return a;
}
