# Optional signalName in sendEvent

applyTo: "**/SendEventValidatorTask.java,**/EventExtractor.java,**/WorkflowNative.java"

---

## Overview

The `sendEvent()` function supports an optional `signalName` parameter. When omitted, the signal name is inferred at runtime by matching the event data structure against the workflow's events record. If ambiguous signal types exist, a compile-time error (WORKFLOW_112) is reported.

## Current Implementation

### 1. Ballerina Layer

#### API Signature ([functions.bal](ballerina/functions.bal))
```ballerina
# Send an event/signal to a running workflow
# + processFunction - The process function identifying the workflow type
# + eventData - The signal data (must contain "id" field for correlation)
# + signalName - Optional signal name. If not provided, inferred from event data structure
# + return - `true` if successful, error otherwise
public isolated function sendEvent(
    function processFunction,
    map<anydata> eventData,
    string? signalName = ()
) returns boolean|error;
```

### 2. Compiler Plugin Layer

#### SendEventValidatorTask.java
Location: [SendEventValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/SendEventValidatorTask.java)

**Purpose**: Validates `sendEvent()` calls at compile-time to detect ambiguous signal scenarios.

```java
public class SendEventValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {
    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionCallExpressionNode)) {
            return;
        }

        FunctionCallExpressionNode callNode = (FunctionCallExpressionNode) context.node();
        
        // Check if this is a sendEvent call
        if (!isSendEventCall(callNode, context.semanticModel())) {
            return;
        }
        
        // Check if signalName is provided (3rd argument)
        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        boolean hasSignalName = arguments.size() >= 3;
        
        if (hasSignalName) {
            // signalName provided - no validation needed
            return;
        }
        
        // Get the process function from first argument
        ExpressionNode processExpr = extractProcessFunctionExpression(arguments);
        Optional<TypeSymbol> typeOpt = context.semanticModel().typeOf(processExpr);
        
        if (typeOpt.isEmpty()) {
            return;
        }
        
        // Get function symbol to examine signature
        Optional<Symbol> symbolOpt = context.semanticModel().symbol(processExpr);
        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return;
        }
        
        FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
        Optional<FunctionTypeSymbol> typeSymbolOpt = functionSymbol.typeDescriptor();
        
        if (typeSymbolOpt.isEmpty()) {
            return;
        }
        
        // Get events record parameter (3rd parameter)
        Optional<List<ParameterSymbol>> paramsOpt = typeSymbolOpt.get().params();
        if (paramsOpt.isEmpty() || paramsOpt.get().size() < 3) {
            // No events parameter - no ambiguity possible
            return;
        }
        
        ParameterSymbol eventsParam = paramsOpt.get().get(2);
        TypeSymbol eventsType = eventsParam.typeDescriptor();
        
        if (eventsType.typeKind() != TypeDescKind.RECORD) {
            return;
        }
        
        RecordTypeSymbol recordType = (RecordTypeSymbol) eventsType;
        Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
        
        if (fields.size() <= 1) {
            // Single signal or none - no ambiguity
            return;
        }
        
        // Check for structural equivalence between signal types
        // Extract constraint types from future<T> fields
        List<TypeSymbol> signalTypes = new ArrayList<>();
        for (RecordFieldSymbol field : fields.values()) {
            TypeSymbol fieldType = field.typeDescriptor();
            if (fieldType.typeKind() == TypeDescKind.FUTURE) {
                FutureTypeSymbol futureType = (FutureTypeSymbol) fieldType;
                Optional<TypeSymbol> constraintOpt = futureType.typeParameter();
                if (constraintOpt.isPresent()) {
                    signalTypes.add(constraintOpt.get());
                }
            }
        }
        
        // Check if any two signal types are structurally equivalent
        boolean hasAmbiguity = checkForStructuralEquivalence(signalTypes);
        
        if (hasAmbiguity) {
            // Report WORKFLOW_112 error
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                WorkflowConstants.WORKFLOW_112,
                "Ambiguous signal types detected. Provide explicit 'signalName' parameter.",
                DiagnosticSeverity.ERROR
            );
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                diagnosticInfo, callNode.location()
            ));
        }
    }
    
    private boolean checkForStructuralEquivalence(List<TypeSymbol> types) {
        // Compare each pair of types for structural equivalence
        for (int i = 0; i < types.size(); i++) {
            for (int j = i + 1; j < types.size(); j++) {
                if (areStructurallyEquivalent(types.get(i), types.get(j))) {
                    return true;  // Found ambiguous pair
                }
            }
        }
        return false;
    }
    
    private boolean areStructurallyEquivalent(TypeSymbol type1, TypeSymbol type2) {
        // Check if two types have the same structure (same fields, same types)
        if (type1.typeKind() != type2.typeKind()) {
            return false;
        }
        
        if (type1.typeKind() == TypeDescKind.RECORD) {
            RecordTypeSymbol record1 = (RecordTypeSymbol) type1;
            RecordTypeSymbol record2 = (RecordTypeSymbol) type2;
            
            Map<String, RecordFieldSymbol> fields1 = record1.fieldDescriptors();
            Map<String, RecordFieldSymbol> fields2 = record2.fieldDescriptors();
            
            if (fields1.size() != fields2.size()) {
                return false;
            }
            
            // Check if all fields match
            for (String fieldName : fields1.keySet()) {
                if (!fields2.containsKey(fieldName)) {
                    return false;
                }
                
                TypeSymbol fieldType1 = fields1.get(fieldName).typeDescriptor();
                TypeSymbol fieldType2 = fields2.get(fieldName).typeDescriptor();
                
                // Recursively check field types
                if (!fieldType1.signature().equals(fieldType2.signature())) {
                    return false;
                }
            }
            
            return true;  // All fields match - structurally equivalent
        }
        
        // For non-record types, compare signatures
        return type1.signature().equals(type2.signature());
    }
}
```

### 3. Native Layer

#### WorkflowNative.java - sendEvent Implementation
Location: [WorkflowNative.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/nativeimpl/WorkflowNative.java)

```java
public static Object sendEvent(
        BFunctionPointer processFunction,
        BMap<BString, Object> eventData,
        Object signalName) {
    
    try {
        // 1. Extract workflow type from process function
        String workflowType = extractProcessName(processFunction);
        
        // 2. Extract workflow ID from event data (for correlation)
        String workflowId = extractWorkflowId(eventData);
        
        // 3. Determine signal name
        String actualSignalName;
        if (signalName != null && signalName instanceof BString) {
            // Explicit signal name provided
            actualSignalName = ((BString) signalName).getValue();
        } else {
            // Infer signal name from event data structure
            actualSignalName = inferSignalName(processFunction, eventData);
        }
        
        // 4. Get workflow client
        WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
        
        // 5. Create workflow stub by ID
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        
        // 6. Send signal
        Object javaEventData = TypesUtil.convertBallerinaToJavaType(eventData);
        stub.signal(actualSignalName, javaEventData);
        
        return true;
        
    } catch (Exception e) {
        return ErrorCreator.createError(
            StringUtils.fromString("Failed to send event: " + e.getMessage())
        );
    }
}

// Infer signal name by matching event data structure to events record
private static String inferSignalName(
        BFunctionPointer processFunction,
        BMap<BString, Object> eventData) throws Exception {
    
    // 1. Get events record type from process function signature
    RecordType eventsRecordType = EventExtractor.getEventsRecordType(processFunction);
    
    if (eventsRecordType == null) {
        throw new IllegalArgumentException("Process has no events parameter");
    }
    
    Map<String, Field> fields = eventsRecordType.getFields();
    
    if (fields.size() == 1) {
        // Single signal - use its name
        return fields.keySet().iterator().next();
    }
    
    // 2. Match event data structure to signal types
    // Extract constraint type from each future<T> field and compare with eventData
    String matchedSignalName = null;
    int matchCount = 0;
    
    for (Map.Entry<String, Field> entry : fields.entrySet()) {
        String fieldName = entry.getKey();
        Type fieldType = entry.getValue().getFieldType();
        
        // Extract T from future<T>
        Type constraintType = extractConstraintType(fieldType);
        
        // Check if eventData structure matches constraintType
        if (isStructuralMatch(eventData, constraintType)) {
            matchedSignalName = fieldName;
            matchCount++;
        }
    }
    
    if (matchCount == 0) {
        throw new IllegalArgumentException(
            "Event data does not match any signal type in process"
        );
    }
    
    if (matchCount > 1) {
        throw new IllegalArgumentException(
            "Event data matches multiple signal types. Provide explicit signalName."
        );
    }
    
    return matchedSignalName;
}
```

## Usage Examples

### Single Signal (Always Inferable)
```ballerina
@workflow:Process
function singleSignalWorkflow(
    workflow:Context ctx,
    Input input,
    record {| future<SignalData> onlySignal; |} events
) returns Result|error {
    SignalData data = check wait events.onlySignal;
    return {status: "completed"};
}

// ✅ signalName omitted - only one signal
SignalData data = {id: workflowId, value: "test"};
_ = check workflow:sendEvent(singleSignalWorkflow, data);
```

### Distinct Signal Types (Structure-Based Inference)
```ballerina
@workflow:Process
function distinctSignalsWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<ApprovalSignal> approval;  // {id, approved, approverName}
        future<PaymentSignal> payment;    // {id, amount, transactionRef}
    |} events
) returns Result|error { }

// ✅ Each type has unique structure - inference works
ApprovalSignal approval = {id: wfId, approved: true, approverName: "John"};
_ = check workflow:sendEvent(distinctSignalsWorkflow, approval);

PaymentSignal payment = {id: wfId, amount: 100.0, transactionRef: "TXN123"};
_ = check workflow:sendEvent(distinctSignalsWorkflow, payment);
```

### Ambiguous Signal Types (Requires Explicit signalName)
```ballerina
@workflow:Process
function ambiguousSignalsWorkflow(
    workflow:Context ctx,
    Input input,
    record {|
        future<SignalType> signal1;  // Same structure
        future<SignalType> signal2;  // Same structure
    |} events
) returns Result|error { }

SignalType data = {id: wfId, value: "test"};

// ❌ COMPILE ERROR: WORKFLOW_112 - Ambiguous without explicit signalName
_ = check workflow:sendEvent(ambiguousSignalsWorkflow, data);

// ✅ FIX: Provide explicit signalName
_ = check workflow:sendEvent(ambiguousSignalsWorkflow, data, "signal1");
```

## Success Criteria

✅ **Compile-Time Validation:**
- Single signal workflows compile without signalName parameter
- Distinct signal types compile without signalName parameter
- Ambiguous signal types produce WORKFLOW_112 error without signalName
- Explicit signalName parameter always compiles successfully

✅ **Runtime Behavior:**
- Single signal: Signal name correctly inferred from field name
- Distinct types: Signal name correctly inferred by matching structure
- Explicit signalName: Uses provided name, skips inference
- Invalid structure: Runtime error if event data doesn't match any signal type
- Multiple matches: Runtime error if event data matches multiple signals (should be caught at compile-time)

✅ **Error Messages:**
- WORKFLOW_112 error clearly indicates ambiguity
- Runtime errors clearly explain mismatch or ambiguity
- Suggests providing explicit signalName as solution
