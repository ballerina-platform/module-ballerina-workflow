// import workflow;

//  import ballerina/jballerina.java;

// # Check if the current workflow execution is in replaying mode.
// #
// # + return - returns true if the workflow is replaying, false otherwise
// #
// # # Deprecated
// # Usage of this function is discouraged. This is an internal function.
// # Writing workflow logic based on this may affect the determinism of the workflow. Hence, this function is marked as deprecated.
// @deprecated
// public function __isReplaying() returns boolean|workflow:NotInWorkflowError {
//     do {
//         workflow:PersistentProvider provider = check getCurrentProvider();
//         workflow:WorkflowInternalOperators internalOperators = check provider.getWorkflowInternalOperators();
//         return internalOperators.isReplaying();
//     } on fail error err {
//         return error workflow:NotInWorkflowError("Failed to get replaying status", err);
//     }
// }

 # Invoke an activity by name with the given arguments.
 #
 # + activity - name of the activity to invoke
 # + args - arguments to pass to the activity
 # + T - dependently typed parameter representing the return type of the activity
 #
 # + return - returns the result of the activity invocation or an error if the invocation fails
 # # Deprecated
 # Usage of this function is discouraged. This is an internal function.
 # Writing workflow logic based on this may affect the determinism of the workflow. Hence, this function is marked as deprecated.
 @deprecated
public isolated function __invokeActivity(string activity, anydata[] args = [], typedesc T = <>) returns T = external;

// # Get the result from the last invocation of the specified activity.
// #
// # + activityId - identifier of the activity
// # + return - returns the result of the last activity invocation or an error if retrieval fails
// # # Deprecated
// # Usage of this function is discouraged. This is an internal function.
// @deprecated
// public function __getResultFromLastActivity(string activityId) returns anydata|workflow:NotInWorkflowError {
//     do {
//         workflow:PersistentProvider provider = check getCurrentProvider();
//         workflow:WorkflowInternalOperators internalOperators = check provider.getWorkflowInternalOperators();
//         return check internalOperators.getResultFromLastActivity(activityId);
//     } on fail error err {
//         return error workflow:NotInWorkflowError("Failed to get result from last activity: " + activityId, err);
//     }
// }

// function getCurrentProvider() returns workflow:PersistentProvider|workflow:NotInWorkflowError {
//     workflow:PersistentProvider? provider = getProviderExternal();
//     if provider is () {
//         return error workflow:NotInWorkflowError("No PersistentProvider");
//     }
//     return provider;
// }

// function getProviderExternal() returns workflow:PersistentProvider? = @java:Method {
//     'class: "io.ballerina.stdlib.workflow.runtime.nativeimpl.PersistentProviderHolder",
//     name: "getProvider"
// } external;