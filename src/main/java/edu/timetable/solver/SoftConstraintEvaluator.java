package edu.timetable.solver;

import java.util.Arrays;

/**
 * Weighted soft-constraint objective (lower is better). Every term is a sum of independent
 * per-entity contributions, so the change caused by moving one or two meetings can be
 * computed by re-scoring only the student groups, faculty members and courses they belong to.
 * Multi-period meetings count every period they occupy.
 *
 * <ul>
 *   <li><b>Batch idle gaps</b>: free periods between a student group's first and last class of
 *       a day.</li>
 *   <li><b>Faculty load spread</b>: how far each faculty member's daily teaching periods are
 *       from the most even split possible, measured as the sum of squared daily counts minus
 *       its minimum. 0 means perfectly even.</li>
 *   <li><b>Session spacing</b>: pairs of meetings of the same course on the same day (1 each)
 *       or on consecutive days (0.25 each).</li>
 *   <li><b>Building changes</b>: consecutive classes of a student group on the same day held in
 *       different buildings.</li>
 *   <li><b>Room fit</b>: periods a class spends in a room with more than twice the seats it
 *       needs, so big halls stay free and small classes are not lost in them.</li>
 * </ul>
 */
public final class SoftConstraintEvaluator {

    public record Weights(double idleGap, double loadSpread, double sessionSpacing, double buildingChange, double roomFit) {
        /** The four original terms, with room fit switched off. */
        public Weights(double idleGap, double loadSpread, double sessionSpacing, double buildingChange) {
            this(idleGap, loadSpread, sessionSpacing, buildingChange, 0);
        }

        public static Weights defaults() {
            return new Weights(3.0, 1.0, 4.0, 2.0, 1.0);
        }
    }

    /** Raw (unweighted) totals of each term, plus the weighted total. */
    public record Breakdown(double idleGaps, double loadSpread, double sessionSpacing,
                            double buildingChanges, double oversizedRooms, double total) {
        public String describe() {
            return String.format("total %.2f  |  idle gaps %.0f, load spread %.0f, session spacing %.2f, building changes %.0f, oversized rooms %.0f",
                    total, idleGaps, loadSpread, sessionSpacing, buildingChanges, oversizedRooms);
        }
    }

    static final double ADJACENT_DAY_PENALTY = 0.25;

    private final Problem p;
    private final Weights w;
    private final int days;
    private final int periods;

    public SoftConstraintEvaluator(Problem problem, Weights weights) {
        this.p = problem;
        this.w = weights;
        this.days = problem.config().days();
        this.periods = problem.config().periodsPerDay();
    }

    public Weights weights() {
        return w;
    }

    public double total(Schedule s) {
        return breakdown(s).total();
    }

    public Breakdown breakdown(Schedule s) {
        double idle = 0, load = 0, spacing = 0, building = 0, oversized = 0;
        for (int g = 0; g < p.groups().length; g++) {
            idle += idleGaps(s, g);
            building += buildingChanges(s, g);
        }
        for (int f = 0; f < p.faculty().length; f++) load += loadSpread(s, f);
        for (int c = 0; c < p.courses().length; c++) spacing += sessionSpacing(s, c);
        for (int m = 0; m < p.meetingCount(); m++) oversized += oversizedRoom(s, m);
        double total = w.idleGap() * idle + w.loadSpread() * load
                + w.sessionSpacing() * spacing + w.buildingChange() * building + w.roomFit() * oversized;
        return new Breakdown(idle, load, spacing, building, oversized, total);
    }

    /** Weighted cost of every entity touched by meetings {@code a} and {@code b} ({@code b} may be -1). */
    double localCost(Schedule s, int a, int b) {
        double cost = 0;
        int[] groupsA = p.groupsOf(a);
        for (int x : groupsA) cost += groupCost(s, x);
        cost += facultyCost(s, p.facultyOf(a)) + courseCost(s, p.courseOf(a)) + w.roomFit() * oversizedRoom(s, a);
        if (b >= 0) {
            cost += w.roomFit() * oversizedRoom(s, b);
            for (int x : p.groupsOf(b)) if (!contains(groupsA, x)) cost += groupCost(s, x);
            if (p.facultyOf(b) != p.facultyOf(a)) cost += facultyCost(s, p.facultyOf(b));
            if (p.courseOf(b) != p.courseOf(a)) cost += courseCost(s, p.courseOf(b));
        }
        return cost;
    }

    private static boolean contains(int[] values, int x) {
        for (int v : values) if (v == x) return true;
        return false;
    }

    private double groupCost(Schedule s, int g) {
        return w.idleGap() * idleGaps(s, g) + w.buildingChange() * buildingChanges(s, g);
    }

    private double facultyCost(Schedule s, int f) {
        return w.loadSpread() * loadSpread(s, f);
    }

    private double courseCost(Schedule s, int c) {
        return w.sessionSpacing() * sessionSpacing(s, c);
    }

    /** Periods meeting m spends in a room with more than twice the seats its students need. */
    int oversizedRoom(Schedule s, int m) {
        int r = s.roomOf(m);
        if (r < 0) return 0;
        return p.room(r).capacity() > 2 * p.meeting(m).studentCount() ? p.durationOf(m) : 0;
    }

    int idleGaps(Schedule s, int group) {
        int[] dayMask = new int[days];
        for (int m : p.meetingsOfGroup(group)) {
            int slot = s.slotOf(m);
            if (slot < 0) continue;
            for (int t = slot; t < slot + p.durationOf(m); t++) dayMask[t / periods] |= 1 << (t % periods);
        }
        int gaps = 0;
        for (int mask : dayMask) {
            if (mask == 0) continue;
            int first = Integer.numberOfTrailingZeros(mask);
            int last = 31 - Integer.numberOfLeadingZeros(mask);
            gaps += (last - first + 1) - Integer.bitCount(mask);
        }
        return gaps;
    }

    int buildingChanges(Schedule s, int group) {
        int[] buildingAt = new int[days * periods];
        Arrays.fill(buildingAt, -1);
        for (int m : p.meetingsOfGroup(group)) {
            int slot = s.slotOf(m);
            if (slot < 0) continue;
            for (int t = slot; t < slot + p.durationOf(m); t++) buildingAt[t] = p.buildingOf(s.roomOf(m));
        }
        int changes = 0;
        for (int d = 0; d < days; d++) {
            int previous = -1;
            for (int t = d * periods; t < (d + 1) * periods; t++) {
                int here = buildingAt[t];
                if (here < 0) continue;
                if (previous >= 0 && previous != here) changes++;
                previous = here;
            }
        }
        return changes;
    }

    int loadSpread(Schedule s, int faculty) {
        int[] perDay = new int[days];
        int total = 0;
        for (int m : p.meetingsOfFaculty(faculty)) {
            int slot = s.slotOf(m);
            if (slot < 0) continue;
            perDay[slot / periods] += p.durationOf(m);
            total += p.durationOf(m);
        }
        int sumSquares = 0;
        for (int c : perDay) sumSquares += c * c;
        int q = total / days, rem = total % days;
        int evenest = rem * (q + 1) * (q + 1) + (days - rem) * q * q;
        return sumSquares - evenest;
    }

    double sessionSpacing(Schedule s, int course) {
        int[] perDay = new int[days];
        for (int m : p.meetingsOfCourse(course)) {
            int slot = s.slotOf(m);
            if (slot >= 0) perDay[slot / periods]++;
        }
        double cost = 0;
        for (int d = 0; d < days; d++) {
            cost += perDay[d] * (perDay[d] - 1) / 2.0;
            if (d + 1 < days) cost += ADJACENT_DAY_PENALTY * perDay[d] * perDay[d + 1];
        }
        return cost;
    }
}
