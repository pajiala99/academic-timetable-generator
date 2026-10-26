package edu.timetable.solver;

import java.util.List;

/**
 * Outcome of the pre-search analysis.
 *
 * @param reasons proofs of infeasibility; if any are present no clash-free timetable can exist
 * @param notes   informational lines (graph size, lower bound, ...)
 */
public record FeasibilityReport(List<String> reasons, List<String> notes,
                                CliqueFinder.Result clique, int slotCount) {
    public FeasibilityReport {
        reasons = List.copyOf(reasons);
        notes = List.copyOf(notes);
    }

    public boolean provenInfeasible() {
        return !reasons.isEmpty();
    }

    /** Minimum number of slots any timetable needs (the clique size). */
    public int lowerBound() {
        return clique.size();
    }
}
