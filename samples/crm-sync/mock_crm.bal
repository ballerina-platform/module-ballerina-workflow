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

import ballerina/uuid;
import ballerina/regex;

// Mock CRM Systems
// Simulates source and target CRM APIs

// In-memory storage for target CRM
final map<TargetContact> targetCrmContacts = {};

# Find contact in target CRM by email
#
# + email - Email to search
# + return - Target contact or () if not found
public function findContactByEmail(string email) returns TargetContact?|error {
    lock {
        foreach TargetContact contact in targetCrmContacts {
            if contact.Email.toLowerAscii() == email.toLowerAscii() {
                return contact;
            }
        }
    }
    return ();
}

# Create new contact in target CRM
#
# + contact - Source contact data
# + return - Created contact ID or error
public function createTargetContact(SourceContact contact) returns string|error {
    lock {
        string newId = uuid:createType1AsString();
        
        TargetContact targetContact = {
            Id: newId,
            Email: contact.email,
            FirstName: contact.firstName,
            LastName: contact.lastName,
            Phone: contact.phone,
            Company: contact.company,
            Source: "SourceCRM"
        };
        
        targetCrmContacts[newId] = targetContact;
        return newId;
    }
}

# Update existing contact in target CRM
#
# + contactId - Target contact ID
# + contact - Updated contact data
# + return - Error if update fails
public function updateTargetContact(string contactId, SourceContact contact) returns error? {
    lock {
        TargetContact? existing = targetCrmContacts[contactId];
        if existing is () {
            return error(string `Contact not found: ${contactId}`);
        }
        
        TargetContact updated = {
            Id: contactId,
            Email: contact.email,
            FirstName: contact.firstName,
            LastName: contact.lastName,
            Phone: contact.phone,
            Company: contact.company,
            Source: existing.Source
        };
        
        targetCrmContacts[contactId] = updated;
    }
}

# Validate contact data
#
# + contact - Contact to validate
# + return - Validation errors (empty if valid)
public function validateContactData(SourceContact contact) returns ValidationError[] {
    ValidationError[] errors = [];
    
    // Email validation
    if contact.email.trim().length() == 0 {
        errors.push({fieldName: "email", message: "Email is required"});
    } else if !isValidEmail(contact.email) {
        errors.push({fieldName: "email", message: "Invalid email format"});
    }
    
    // LastName required
    if contact.lastName.trim().length() == 0 {
        errors.push({fieldName: "lastName", message: "Last name is required"});
    }
    
    return errors;
}

# Simple email validation
isolated function isValidEmail(string email) returns boolean {
    string emailPattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    return regex:matches(email, emailPattern);
}

# Reset CRM data (for testing)
public function resetCrmData() {
    lock {
        targetCrmContacts.removeAll();
    }
}
