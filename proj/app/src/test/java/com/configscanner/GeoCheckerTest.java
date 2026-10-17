package com.configscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GeoCheckerTest {

    @Test
    public void ipv6ReceivesLongerGeoWindowAndVoteGrace() {
        assertEquals(10, GeoChecker.geoTimeoutSeconds(10, false));
        assertEquals(15, GeoChecker.geoTimeoutSeconds(10, true));
        assertEquals(20, GeoChecker.geoTimeoutSeconds(60, true));
        assertEquals(6_000L, GeoChecker.firstAnswerGraceMillis(false));
        assertEquals(12_000L, GeoChecker.firstAnswerGraceMillis(true));
        assertEquals(6, GeoChecker.completionPollBudget(7, 1, 0));
        assertEquals(4, GeoChecker.completionPollBudget(7, 5, 2));
    }

    @Test
    public void oneGeoProviderDoesNotAssignCountryButIsVisibleInDiagnostics() {
        List<String[]> votes = Collections.singletonList(
                new String[]{"FR", "France", "192.0.2.13", ""});

        GeoChecker.Result result = GeoChecker.makeResult(votes);

        assertFalse(result.ok);
        assertEquals("", result.code);
        assertEquals(1, result.votes);
        assertEquals(1, result.answered);
        assertTrue(result.singleVote);
    }

    @Test
    public void twoMatchingGeoProvidersCanStillAssignCountry() {
        List<String[]> votes = Arrays.asList(
                new String[]{"FR", "France", "192.0.2.13", ""},
                new String[]{"FR", "France", "192.0.2.13", ""},
                new String[]{"NL", "Netherlands", "192.0.2.20", ""});

        GeoChecker.Result result = GeoChecker.makeResult(votes);

        assertTrue(result.ok);
        assertEquals("FR", result.code);
        assertEquals(2, result.votes);
        assertEquals(3, result.answered);
        assertFalse(result.singleVote);
    }
}
