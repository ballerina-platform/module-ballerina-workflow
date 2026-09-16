/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime.nativeimpl;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * The one eligibility rule every completion path applies.
 */
public class TaskAssignmentTest {

    private static final String SUBJECT = "task 't1'";

    private static TaskAssignment of(List<String> roles, List<String> users, List<String> exUsers,
                                     List<String> exRoles) {
        return new TaskAssignment(roles, users, exUsers, exRoles);
    }

    @Test
    public void openTaskAdmitsAnyCaller() {
        TaskAssignment open = of(List.of(), List.of(), List.of(), List.of());
        Assert.assertNull(open.denial(List.of("anyone"), null, SUBJECT));
        Assert.assertNull(open.denial(List.of(), "alice", SUBJECT));
    }

    @Test
    public void callerWithoutRolesIsNotChecked() {
        TaskAssignment strict = of(List.of("manager"), List.of(), List.of("alice"), List.of());
        Assert.assertNull(strict.denial(null, "alice", SUBJECT));
    }

    @Test
    public void roleIntersectionAdmits() {
        TaskAssignment a = of(List.of("manager", "finance"), List.of(), List.of(), List.of());
        Assert.assertNull(a.denial(List.of("guest", "finance"), null, SUBJECT));
    }

    @Test
    public void roleMismatchNamesTheRequiredRoles() {
        TaskAssignment a = of(List.of("manager"), List.of(), List.of(), List.of());
        String denial = a.denial(List.of("guest"), "bob", SUBJECT);
        Assert.assertNotNull(denial);
        Assert.assertTrue(denial.contains("required role"), denial);
        Assert.assertTrue(denial.contains("manager"), denial);
    }

    @Test
    public void excludedRoleDeniesEvenWhenAnotherRoleMatches() {
        TaskAssignment a = of(List.of("manager"), List.of(), List.of(), List.of("intern"));
        String denial = a.denial(List.of("manager", "intern"), "bob", SUBJECT);
        Assert.assertNotNull(denial);
        Assert.assertTrue(denial.contains("excluded"), denial);
    }

    @Test
    public void excludedUserDeniesAndRequiresAnIdentity() {
        TaskAssignment a = of(List.of("manager"), List.of(), List.of("alice"), List.of());
        Assert.assertNotNull(a.denial(List.of("manager"), "alice", SUBJECT));
        Assert.assertNotNull(a.denial(List.of("manager"), null, SUBJECT));
        Assert.assertNull(a.denial(List.of("manager"), "bob", SUBJECT));
    }

    @Test
    public void namedUserAdmitsWithoutARole() {
        TaskAssignment a = of(List.of(), List.of("alice"), List.of(), List.of());
        Assert.assertNull(a.denial(List.of("guest"), "alice", SUBJECT));
        Assert.assertNotNull(a.denial(List.of("guest"), "bob", SUBJECT));
        String anonymous = a.denial(List.of("guest"), null, SUBJECT);
        Assert.assertNotNull(anonymous);
        Assert.assertTrue(anonymous.contains("no user id"), anonymous);
    }

    @Test
    public void usersAndRolesAreAlternatives() {
        TaskAssignment a = of(List.of("manager"), List.of("alice"), List.of(), List.of());
        Assert.assertNull(a.denial(List.of("manager"), "bob", SUBJECT));
        Assert.assertNull(a.denial(List.of("guest"), "alice", SUBJECT));
        Assert.assertNotNull(a.denial(List.of("guest"), "bob", SUBJECT));
    }
}
