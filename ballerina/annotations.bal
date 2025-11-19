// Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
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

# Workflow activity function
#
# An annotation to mark a function as a workflow activity.
public annotation Activity on function;

# Workflow start event function
#
# An annotation to mark a remote function as a workflow start event.
public annotation StartEvent on service remote function;

# Workflow signal function
#
# An annotation to mark a remote function as a workflow signal.
public annotation Signal on service remote function;

# Workflow query function
#
# An annotation to mark a remote function as a workflow query.
public annotation Query on service remote function;

// TODO: Add UpdateEvent in future.

# Correlation parameter
#
# An annotation to mark a parameter as a correlation parameter.
public annotation Correlation on parameter;
