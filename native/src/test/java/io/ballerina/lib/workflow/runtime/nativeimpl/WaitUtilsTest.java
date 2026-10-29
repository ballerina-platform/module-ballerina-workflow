/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime.nativeimpl;

import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.math.BigDecimal;

public class WaitUtilsTest {

    private static BMap<BString, Object> duration(long years, long months, long weeks, long days, long hours,
                                                   long minutes, String seconds) {
        BMap<BString, Object> record = ValueCreator.createMapValue(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA));
        record.put(StringUtils.fromString("years"), years);
        record.put(StringUtils.fromString("months"), months);
        record.put(StringUtils.fromString("weeks"), weeks);
        record.put(StringUtils.fromString("days"), days);
        record.put(StringUtils.fromString("hours"), hours);
        record.put(StringUtils.fromString("minutes"), minutes);
        record.put(StringUtils.fromString("seconds"), ValueCreator.createDecimalValue(new BigDecimal(seconds)));
        return record;
    }

    @Test
    public void everyFixedLengthUnitCounts() {
        Assert.assertEquals(WaitUtils.durationToMillis(duration(0, 0, 1, 2, 3, 4, "5.5")),
                604_800_000L + 2 * 86_400_000L + 3 * 3_600_000L + 4 * 60_000L + 5_500L);
        Assert.assertEquals(WaitUtils.durationToMillis(duration(0, 0, 0, 1, 0, 0, "0")), 86_400_000L);
    }

    @Test
    public void monthsAndYearsAreRefusedUnderTheCallerName() {
        IllegalArgumentException refused = Assert.expectThrows(IllegalArgumentException.class,
                () -> WaitUtils.durationToMillis(duration(0, 1, 0, 0, 0, 0, "0"), "A sleep"));
        Assert.assertEquals(refused.getMessage(), "A sleep does not support months or years");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> WaitUtils.durationToMillis(duration(1, 0, 0, 0, 0, 0, "0")));
    }

    @Test
    public void secondsThatOverflowMillisecondsAreRefused() {
        // 2^64 / 1000 + 5 seconds wrapped to 5000 ms with a modular conversion
        IllegalArgumentException refused = Assert.expectThrows(IllegalArgumentException.class,
                () -> WaitUtils.durationToMillis(duration(0, 0, 0, 0, 0, 0, "18446744073709556.616"), "A sleep"));
        Assert.assertEquals(refused.getMessage(), "A sleep is too large to represent in milliseconds");
        Assert.assertEquals(WaitUtils.durationToMillis(duration(0, 0, 0, 0, 0, 0, "1.2345")), 1_234L);
    }

    @Test
    public void theLegacySleepRoundsTheTotalHalfToEven() {
        // The pre-change Ballerina `<int>(totalSeconds * 1000)`: weeks and days ignored, ties to even
        Assert.assertEquals(WaitUtils.legacySleepDurationToMillis(duration(0, 0, 1, 2, 1, 0, "0")), 3_600_000L);
        Assert.assertEquals(WaitUtils.legacySleepDurationToMillis(duration(0, 0, 0, 0, 0, 0, "0.0006")), 1L);
        Assert.assertEquals(WaitUtils.legacySleepDurationToMillis(duration(0, 0, 0, 0, 0, 0, "0.0005")), 0L);
        Assert.assertEquals(WaitUtils.legacySleepDurationToMillis(duration(0, 0, 0, 0, 0, 0, "0.0015")), 2L);
    }

    @Test
    public void theLegacyBoundIgnoresWeeksAndDays() {
        // What executions started before the units change recorded, and must keep on replay
        Assert.assertEquals(WaitUtils.legacyDurationToMillis(duration(0, 0, 1, 2, 3, 0, "0")), 3 * 3_600_000L);
    }
}
