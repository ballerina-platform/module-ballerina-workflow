import ballerina/workflow;

public type Person1 record {|
    string name;
    int age;
|};

@workflow:Activity
public isolated function performActivity(int a, string name) returns int {
    return a;
}

@workflow:Activity
public isolated function activityReturnsArray() returns int[] {
    return [1, 2];
}

@workflow:Activity
public isolated function activityReturnsUserDefinedTypeArray(int a, string name) returns Person1[] {
    return [{ name, age: a }];
}

@workflow:Activity
public isolated function activityReturnsNil(int a, string name)  {
}

@workflow:Activity
public isolated function activityReturnsUserDefinedType(int a, string name) returns Person1 {
    return { name, age: a };
}

@workflow:Activity
public isolated function activityReturnsMap(int a, string name) returns map<anydata> {
    return { name, age: a };
}

@workflow:Activity
public isolated function activityReturnsTable(int a, string name) returns table<map<anydata>> {
    return table [{ name, age: a }];
}
