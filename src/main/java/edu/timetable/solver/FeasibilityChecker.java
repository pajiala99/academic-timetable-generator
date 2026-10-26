package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Necessary conditions for feasibility, checked before any search. Each failed check comes
 * with a concrete explanation the user can act on. Passing every check does not prove that a
 * timetable exists (the problem is NP-complete); it only means no cheap obstruction was found.
 *
 * <ol>
 *   <li>Every meeting has at least one (slot, room) left after the unary constraints.</li>
 *   <li>Maximum-clique bound: a clique of k pairwise-conflicting meetings needs k slots.</li>
 *   <li>Hall's condition on slots for every batch, every faculty member and the maximum clique:
 *       meetings that must be in distinct slots need a matching into their allowed slots.</li>
 *   <li>Hall's condition on rooms: every meeting needs its own (slot, room) pair.</li>
 * </ol>
 */
public final class FeasibilityChecker {
    private static final int LIST_LIMIT = 4;

    private FeasibilityChecker() {
    }

    public static FeasibilityReport check(Problem p, long cliqueTimeLimitMs) {
        List<String> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int slots = p.slotCount();
        notes.add(String.format("Conflict graph: %d meetings (vertices), %d conflicts (edges); %d slots (colours), %d rooms.",
                p.meetingCount(), p.edgeCount(), slots, p.roomCount()));

        checkEmptyDomains(p, reasons);

        CliqueFinder.Result clique = CliqueFinder.find(p, cliqueTimeLimitMs);
        notes.add(String.format("Lower bound: %s of %d mutually conflicting meetings (%s), so at least %d slots are needed; the week has %d. [%d nodes, %d ms]",
                clique.optimal() ? "maximum clique (proven)" : "largest clique found within the time limit",
                clique.size(), describe(p, clique.members()), clique.size(), slots, clique.nodes(), clique.millis()));
        if (clique.size() > slots) {
            reasons.add(String.format("Clique bound violated: %d meetings pairwise clash (%s) but the week has only %d slots.",
                    clique.size(), describe(p, clique.members()), slots));
        }

        for (int b = 0; b < p.batches().length; b++)
            hallOnSlots(p, p.meetingsOfBatch(b), "Batch " + p.batches()[b].name(), reasons);
        for (int f = 0; f < p.faculty().length; f++)
            hallOnSlots(p, p.meetingsOfFaculty(f), p.faculty()[f].name(), reasons);
        if (clique.size() <= slots) hallOnSlots(p, clique.members(), "The maximum clique", reasons);

        hallOnRooms(p, reasons);

        if (reasons.isEmpty()) {
            notes.add("All necessary conditions hold. They cannot prove feasibility, so the search decides.");
        }
        return new FeasibilityReport(reasons, notes, clique, slots);
    }

    private static void checkEmptyDomains(Problem p, List<String> reasons) {
        for (int c = 0; c < p.courses().length; c++) {
            int[] ms = p.meetingsOfCourse(c);
            if (ms.length == 0 || !p.baseDomain(ms[0]).isEmpty()) continue;
            Meeting m = p.meeting(ms[0]);
            Course course = m.course();
            RoomType type = course.roomType();
            int students = m.studentCount();
            List<Room> ofType = Arrays.stream(p.rooms()).filter(r -> r.type() == type).toList();
            boolean roomFits = ofType.stream().anyMatch(r -> r.capacity() >= students);
            if (ofType.isEmpty()) {
                reasons.add(String.format("%s needs a %s room but the department has none.", course.code(), type));
            } else if (!roomFits) {
                int max = ofType.stream().mapToInt(Room::capacity).max().orElse(0);
                reasons.add(String.format("%s (%s, %d students) needs a %s room seating %d, but the largest %s room seats %d.",
                        course.code(), m.batchNames(), students, type, students, type, max));
            } else {
                reasons.add(String.format("%s: %s is unavailable in every slot of the week.", course.code(), m.faculty().name()));
            }
        }
    }

    /** Meetings of {@code group} must use distinct slots: is there a matching into allowed slots? */
    private static void hallOnSlots(Problem p, int[] group, String who, List<String> reasons) {
        int[] members = Arrays.stream(group).filter(m -> !p.baseDomain(m).isEmpty()).toArray();
        BitSet[] rows = new BitSet[members.length];
        for (int i = 0; i < members.length; i++) rows[i] = p.slotsOf(p.baseDomain(members[i]));
        BipartiteMatching matching = BipartiteMatching.fromBitSets(rows, p.slotCount());
        if (matching.isLeftPerfect()) return;

        BipartiteMatching.Deficiency d = matching.deficiency();
        int[] stuck = Arrays.stream(d.left()).map(i -> members[i]).toArray();
        if (d.right().length == p.slotCount() && stuck.length == members.length) {
            reasons.add(String.format("%s has %d meetings that must all be in different slots, but the week has only %d slots.",
                    who, members.length, p.slotCount()));
        } else {
            String slotNames = IntStream.of(d.right()).mapToObj(s -> p.config().slotLabel(s)).collect(Collectors.joining(", "));
            reasons.add(String.format("%s: %d meetings that must be in different slots can only use %d slot(s) [%s]: %s.",
                    who, stuck.length, d.right().length, abbreviate(slotNames, 120), labels(p, stuck)));
        }
    }

    /** Each (slot, room) pair hosts at most one meeting: is there a matching of meetings into pairs? */
    private static void hallOnRooms(Problem p, List<String> reasons) {
        int[] members = IntStream.range(0, p.meetingCount()).filter(m -> !p.baseDomain(m).isEmpty()).toArray();
        BitSet[] rows = new BitSet[members.length];
        for (int i = 0; i < members.length; i++) rows[i] = p.baseDomain(members[i]);
        BipartiteMatching matching = BipartiteMatching.fromBitSets(rows, p.slotCount() * p.roomCount());
        if (matching.isLeftPerfect()) return;

        BipartiteMatching.Deficiency d = matching.deficiency();
        TreeSet<String> roomNames = new TreeSet<>();
        for (int pair : d.right()) roomNames.add(p.room(p.roomOfPair(pair)).code());
        int[] stuck = Arrays.stream(d.left()).map(i -> members[i]).toArray();
        reasons.add(String.format("Not enough rooms: %d meetings can only use rooms %s, which offer just %d (slot, room) combinations: %s.",
                stuck.length, roomNames, d.right().length, labels(p, stuck)));
    }

    private static String describe(Problem p, int[] meetings) {
        Map<String, Integer> byBatch = new LinkedHashMap<>();
        Map<String, Integer> byFaculty = new LinkedHashMap<>();
        for (int m : meetings) {
            for (Batch b : p.meeting(m).batches()) byBatch.merge(b.name(), 1, Integer::sum);
            byFaculty.merge(p.meeting(m).faculty().name(), 1, Integer::sum);
        }
        if (meetings.length == 0) return "empty";
        // A batch (or teacher) attending every meeting of the clique explains it on its own.
        for (Map.Entry<String, Integer> e : byBatch.entrySet())
            if (e.getValue() == meetings.length) return "all attended by batch " + e.getKey();
        if (byFaculty.size() == 1) return "all taught by " + byFaculty.keySet().iterator().next();
        return "no single batch or teacher is in all of them; meetings per batch " + byBatch;
    }

    private static String labels(Problem p, int[] meetings) {
        String shown = Arrays.stream(meetings).limit(LIST_LIMIT).mapToObj(m -> p.meeting(m).label()).collect(Collectors.joining("; "));
        return meetings.length > LIST_LIMIT ? shown + "; ... (" + (meetings.length - LIST_LIMIT) + " more)" : shown;
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
