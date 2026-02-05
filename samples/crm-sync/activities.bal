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

// Activity Implementations
// Activities interact with CRM systems

# Validate contact data
#
# + contact - Source contact to validate
# + return - Error if validation fails
@workflow:Activity
function validateContact(SourceContact contact) returns error? {
    io:println(string `[Activity] Validating contact: ${contact.email}`);
    
    ValidationError[] errors = validateContactData(contact);
    
    if errors.length() > 0 {
        string errorMsg = "";
        foreach ValidationError err in errors {
            errorMsg += string `${err.fieldName}: ${err.message}; `;
        }
        return error(string `Contact validation failed: ${errorMsg}`);
    }
    
    io:println(string `[Activity] Contact validation passed`);
}

# Find contact in target CRM by email
#
# + email - Email to search
# + return - Target contact or () if not found
@workflow:Activity
function findTargetContact(string email) returns TargetContact?|error {
    io:println(string `[Activity] Searching for contact in target CRM: ${email}`);
    
    TargetContact? contact = check findContactByEmail(email);
    
    if contact is TargetContact {
        io:println(string `[Activity] Contact found in target CRM: ${contact.Id ?: "unknown"}`);
    } else {
        io:println(string `[Activity] Contact not found in target CRM`);
    }
    
    return contact;
}

# Create new contact in target CRM
#
# + contact - Source contact data
# + return - Created contact ID or error
@workflow:Activity
function createContact(SourceContact contact) returns string|error {
    io:println(string `[Activity] Creating new contact in target CRM: ${contact.email}`);
    
    string contactId = check createTargetContact(contact);
    
    io:println(string `[Activity] Contact created with ID: ${contactId}`);
    
    return contactId;
}

# Update existing contact in target CRM
#
# + contactId - Target contact ID
# + contact - Updated source contact data
# + return - Error if update fails
@workflow:Activity
function updateContact(string contactId, SourceContact contact) returns error? {
    io:println(string `[Activity] Updating contact in target CRM: ${contactId}`);
    
    check updateTargetContact(contactId, contact);
    
    io:println(string `[Activity] Contact updated successfully`);
}
