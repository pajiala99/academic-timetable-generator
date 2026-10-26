package edu.timetable.solver;

import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableEngineTest {

    @Test
    void generatesAndImprovesTheSampleDepartment() {
        List<String> lines = new ArrayList<>();
        TimetableEngine.Result r = TimetableEngine.run(SampleData.department(), TimetableEngine.Options.defaults(), lines::add, null);
        assertEquals(TimetableEngine.Outcome.SUCCESS, r.outcome());
        assertEquals(136, r.entries().size());
        assertTrue(r.breakdown().total() < r.anneal().initial().total());
        assertEquals(r.log(), lines);

        // Round trip through persisted entries reproduces a valid timetable.
        Schedule reloaded = r.problem().fromEntries(r.entries());
        assertEquals(List.of(), HardConstraintValidator.violations(r.problem(), reloaded));
    }

    @Test
    void stopsBeforeSearchWhenInfeasibilityIsProven() {
        TimetableEngine.Result r = TimetableEngine.run(SampleData.overloaded(), TimetableEngine.Options.defaults(), null, null);
        assertEquals(TimetableEngine.Outcome.INFEASIBLE_PRECHECK, r.outcome());
        assertFalse(r.success());
        assertNull(r.search());
        assertTrue(r.precheck().reasons().stream().anyMatch(s -> s.contains("CSE-3A")));
        assertTrue(r.entries().isEmpty());
    }
}
