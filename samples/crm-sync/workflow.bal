// Copyright (c) 2025 WSO2 LLC. (http://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/workflow;
import ballerina/io;

// CRM Contact Sync Workflow
// Syncs contacts from source CRM to target CRM

# Sync Contact Workflow
# Syncs a contact from source to target CRM:
# 1. Validates contact data
# 2. Checks if contact exists in target
# 3. Updates existing or creates new contact
#
# + ctx - Workflow context for calling activities
# + contact - Source contact to sync
# + return - Sync result or error
@workflow:Process
function syncContact(workflow:Context ctx, SourceContact contact) returns SyncResult|error {
    io:println(string `[Workflow] Starting contact sync for: ${contact.email}`);

    // Step 1: Validate contact data
    error? validationResult = ctx->callActivity(validateContact, {"contact": contact});
    if validationResult is error {
        return {
            sourceContactId: contact.id,
            targetContactId: (),
            status: "FAILED",
            message: "Validation failed",
            errorMessage: validationResult.message()
        };
    }

    // Step 2: Check if contact exists in target CRM
    TargetContact? existingContact = check ctx->callActivity(findTargetContact, {"email": contact.email});

    if existingContact is TargetContact {
        // Step 3a: Update existing contact
        error? updateResult = ctx->callActivity(updateContact, {"contactId": existingContact.Id ?: "", "contact": contact});
        check updateResult;
        
        io:println(string `[Workflow] Contact updated: ${contact.id}`);
        
        return {
            sourceContactId: contact.id,
            targetContactId: existingContact.Id,
            status: "UPDATED",
            message: "Contact updated successfully in target CRM",
            errorMessage: ()
        };
    } else {
        // Step 3b: Create new contact
        string newContactId = check ctx->callActivity(createContact, {"contact": contact});
        
        io:println(string `[Workflow] New contact created: ${newContactId}`);
        
        return {
            sourceContactId: contact.id,
            targetContactId: newContactId,
            status: "CREATED",
            message: "Contact created successfully in target CRM",
            errorMessage: ()
        };
    }
}
