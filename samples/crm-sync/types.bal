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

// Type Definitions for CRM Sync Workflow

# Represents a contact in the source CRM
#
# + id - Unique contact identifier in source CRM
# + email - Contact email (used for matching)
# + firstName - First name
# + lastName - Last name
# + phone - Phone number (optional)
# + company - Company name (optional)
public type SourceContact record {|
    readonly string id;  // readonly for correlation
    string email;
    string firstName;
    string lastName;
    string? phone;
    string? company;
|};

# Represents a contact in the target CRM
#
# + Id - Unique contact identifier in target CRM
# + Email - Contact email
# + FirstName - First name
# + LastName - Last name
# + Phone - Phone number (optional)
# + Company - Company name (optional)
# + Source - Origin system identifier
public type TargetContact record {|
    string? Id;  // null for new contacts
    string Email;
    string FirstName;
    string LastName;
    string? Phone;
    string? Company;
    string Source;
|};

# Represents the sync operation result
#
# + sourceContactId - Source contact identifier
# + targetContactId - Target contact identifier (if created/updated)
# + status - Sync status (CREATED, UPDATED, SKIPPED, FAILED)
# + message - Descriptive message
# + errorMessage - Error details (if failed)
public type SyncResult record {|
    string sourceContactId;
    string? targetContactId;
    string status;
    string message;
    string? errorMessage;
|};

# Contact validation errors
#
# + fieldName - Field name with error
# + message - Error description
public type ValidationError record {|
    string fieldName;
    string message;
|};
