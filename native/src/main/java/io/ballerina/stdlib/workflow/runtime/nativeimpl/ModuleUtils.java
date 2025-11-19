/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.stdlib.workflow.runtime.nativeimpl;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Module;
import io.ballerina.stdlib.workflow.runtime.commons.Constants;

import static io.ballerina.runtime.api.constants.RuntimeConstants.ORG_NAME_SEPARATOR;

/**
 * This class will hold module related utility functions.
 *
 * @since 0.1.0
 */
public class ModuleUtils {

    private static Module workflowModule;
    private static String packageIdentifier;

    private ModuleUtils() {
    }

    /**
     * Sets the workflow module and initializes the package identifier.
     *
     * @param env the Ballerina environment
     * @since 0.1.0
     */
    public static void setModule(Environment env) {
        workflowModule = env.getCurrentModule();
        packageIdentifier = Constants.WORKFLOW_PACKAGE_ORG + ORG_NAME_SEPARATOR + Constants.WORKFLOW_PACKAGE_NAME +
                Constants.COLON + workflowModule.getMajorVersion();
    }

    /**
     * Gets ballerina workflow package.
     *
     * @return workflow package
     * @since 0.1.0
     */
    public static Module getPackage() {
        return workflowModule;
    }

    /**
     * Gets ballerina workflow package identifier.
     *
     * @return workflow package identifier
     * @since 0.1.0
     */
    public static String getPackageIdentifier() {
        return packageIdentifier;
    }
}
