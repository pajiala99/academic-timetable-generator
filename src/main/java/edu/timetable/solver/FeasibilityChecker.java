package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;

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
 * <p>The checks work on meeting <i>pieces</i>: a meeting of length d is d unit pieces, piece k
 * occupying the k-th period of the meeting. In any timetable the pieces of a clique, of one
 * student group or of one teacher sit in distinct slots, and every piece has its own
 * (slot, room) pair, so each condition below is necessary for meetings of any length.
 *
 * <ol>
 *   <li>Every meeting has at least one start slot and one suitable room.</li>
 *   <li>Maximum-clique bound: a clique of k pairwise-conflicting pieces needs k slots.</li>
 *   <li>Hall's condition on slots for every student group, every teacher and the maximum clique.</li>
 *   <li>Hall's condition on rooms: every piece needs its own (slot, room) pair.</li>
 *   <li>Long sessions: a room can host at most {@code periods / 2} sessions of two or more
 *       periods on one day (3 in a 7-period day; with a lunch break, each half counts
 *       separately), so long sessions need their own room-day
 *       places too. This catches rooms that have enough free periods in total but cannot fit
 *       one more double period anywhere.</li>
 *   <li>Daily limits: a teacher's weekly periods must fit under their per-day limit.</li>
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
        notes.add(String.format("Conflict graph: %d meetings (vertices), %d conflicts (edges), %d teaching periods; %d slots (colours), %d rooms.",
                p.meetingCount(), p.edgeCount(), p.pieceCount(), slots, p.roomCount()));

        checkEmptyDomains(p, reasons);

        CliqueFinder.Result clique = CliqueFinder.find(p, cliqueTimeLimitMs);
        int[] cliqueMeetings = meetingsOf(p, clique.members());
        notes.add(String.format("Lower bound: %s of %d mutually conflicting periods (%s), so at least %d slots are needed; the week has %d. [%d nodes, %d ms]",
                clique.optimal() ? "maximum clique (proven)" : "largest clique found within the time limit",
                clique.size(), describe(p, cliqueMeetings), clique.size(), slots, clique.nodes(), clique.millis()));
        if (clique.size() > slots) {
            reasons.add(String.format("Clique bound violated: %d teaching periods pairwise clash (%s) but the week has only %d slots.",
                    clique.size(), describe(p, cliqueMeetings), slots));
        }

        for (int g = 0; g < p.groups().length; g++) {
            Batch b = p.groups()[g];
            hallOnSlots(p, p.meetingsOfGroup(g), (b.isGroup() ? "Lab group " : "Batch ") + b.name(), reasons);
        }
        for (int f = 0; f < p.faculty().length; f++)
            hallOnSlots(p, p.meetingsOfFaculty(f), p.faculty()[f].name(), reasons);
        if (clique.size() <= slots) hallOnSlotsForPieces(p, clique.members(), "The maximum clique", reasons);

        hallOnRooms(p, reasons);
        hallOnLongSessions(p, reasons);
        checkDailyLimits(p, reasons);

        if (reasons.isEmpty()) {
            notes.add("All necessary conditions hold. They cannot prove feasibility, so the search decides.");
        }
        return new FeasibilityReport(reasons, notes, clique, slots);
    }

    private static void checkEmptyDomains(Problem p, List<String> reasons) {
        int periods = p.config().periodsPerDay();
        for (int c = 0; c < p.courses().length; c++) {
            int[] ms = p.meetingsOfCourse(c);
            if (ms.length == 0 || p.hasOptions(ms[0])) continue;
            Meeting m = p.meeting(ms[0]);
            Course course = m.course();
            int length = course.periodsPerMeeting();
            if (p.suitableRooms(ms[0]).length == 0) {
                RoomType type = course.roomType();
                int students = m.studentCount();
                List<Room> ofType = Arrays.stream(p.rooms()).filter(r -> r.type() == type).toList();
                if (ofType.isEmpty()) {
                    reasons.add(String.format("%s needs a %s room but the department has none.", course.code(), type));
                } else {
                    int max = ofType.stream().mapToInt(Room::capacity).max().orElse(0);
                    reasons.add(String.format("%s (%s, %d students) needs a %s room seating %d, but the largest %s room seats %d.",
                            course.code(), m.batchNames(), students, type, students, type, max));
                }
            } else if (length > periods) {
                reasons.add(String.format("%s has %d-period sessions, but a day has only %d periods.", course.code(), length, periods));
            } else if (length == 1) {
                reasons.add(String.format("%s: %s is unavailable in every slot of the week.", course.code(), m.faculty().name()));
            } else {
                reasons.add(String.format("%s has %d-period sessions, but %s never has %d consecutive free periods on one day.",
                        course.code(), length, m.faculty().name(), length));
            }
        }
        for (Faculty f : p.faculty()) {
            if (!f.hasDailyLimit()) continue;
            for (Course c : p.courses()) {
                if (c.facultyId() == f.id() && c.periodsPerMeeting() > f.maxPerDay()) {
                    reasons.add(String.format("%s has %d-period sessions, but %s may teach at most %d periods a day.",
                            c.code(), c.periodsPerMeeting(), f.name(), f.maxPerDay()));
                }
            }
        }
    }

    /** The pieces of {@code meetings} must use distinct slots: is there a matching into allowed slots? */
    private static void hallOnSlots(Problem p, int[] meetings, String who, List<String> reasons) {
        BitSet wanted = new BitSet(p.meetingCount());
        for (int m : meetings) wanted.set(m);
        int[] pieces = IntStream.range(0, p.pieceCount()).filter(x -> wanted.get(p.pieceMeeting(x))).toArray();
        hallOnSlotsForPieces(p, pieces, who, reasons);
    }

    private static void hallOnSlotsForPieces(Problem p, int[] allPieces, String who, List<String> reasons) {
        int[] pieces = Arrays.stream(allPieces).filter(x -> p.hasOptions(p.pieceMeeting(x))).toArray();
        BitSet[] rows = new BitSet[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            int m = p.pieceMeeting(pieces[i]), k = p.pieceOffset(pieces[i]);
            rows[i] = new BitSet(p.slotCount());
            for (int s : p.allowedStarts(m)) rows[i].set(s + k);
        }
        BipartiteMatching matching = BipartiteMatching.fromBitSets(rows, p.slotCount());
        if (matching.isLeftPerfect()) return;

        BipartiteMatching.Deficiency d = matching.deficiency();
        int[] stuck = meetingsOf(p, Arrays.stream(d.left()).map(i -> pieces[i]).toArray());
        if (d.right().length == p.slotCount() && d.left().length == pieces.length) {
            reasons.add(String.format("%s has %d teaching periods that must all be in different slots, but the week has only %d slots.",
                    who, pieces.length, p.slotCount()));
        } else {
            String slotNames = IntStream.of(d.right()).mapToObj(s -> p.config().slotLabel(s)).collect(Collectors.joining(", "));
            reasons.add(String.format("%s: %d teaching periods that must be in different slots can only use %d slot(s) [%s]: %s.",
                    who, d.left().length, d.right().length, abbreviate(slotNames, 120), labels(p, stuck)));
        }
    }

    /** Each (slot, room) pair hosts at most one piece: is there a matching of pieces into pairs? */
    private static void hallOnRooms(Problem p, List<String> reasons) {
        int[] pieces = IntStream.range(0, p.pieceCount()).filter(x -> p.hasOptions(p.pieceMeeting(x))).toArray();
        int[][] adj = new int[pieces.length][];
        for (int i = 0; i < pieces.length; i++) {
            int m = p.pieceMeeting(pieces[i]), k = p.pieceOffset(pieces[i]);
            int[] starts = p.allowedStarts(m), rooms = p.suitableRooms(m);
            int[] row = new int[starts.length * rooms.length];
            int n = 0;
            for (int s : starts) for (int r : rooms) row[n++] = p.pair(s + k, r);
            adj[i] = row;
        }
        BipartiteMatching matching = new BipartiteMatching(adj, p.slotCount() * p.roomCount());
        if (matching.isLeftPerfect()) return;

        BipartiteMatching.Deficiency d = matching.deficiency();
        TreeSet<String> roomNames = new TreeSet<>();
        for (int pair : d.right()) roomNames.add(p.room(p.roomOfPair(pair)).code());
        int[] stuck = meetingsOf(p, Arrays.stream(d.left()).map(i -> pieces[i]).toArray());
        reasons.add(String.format("Not enough rooms: %d teaching periods can only use rooms %s, which offer just %d (slot, room) combinations: %s.",
                d.left().length, roomNames, d.right().length, labels(p, stuck)));
    }

    /**
     * The sessions a room hosts on one day are disjoint runs of periods, so at most
     * {@code periods / 2} of them last two periods or more. Numbering a room-day's long sessions
     * in order of start gives each one its own (room, day, index) place: a matching of long
     * sessions into such places must exist.
     */
    private static void hallOnLongSessions(Problem p, List<String> reasons) {
        int periods = p.config().periodsPerDay(), days = p.config().days(), perDay = p.config().longSessionsPerDay();
        int[] longOnes = IntStream.range(0, p.meetingCount()).filter(m -> p.durationOf(m) > 1 && p.hasOptions(m)).toArray();
        if (longOnes.length == 0 || perDay == 0) return;
        int[][] adj = new int[longOnes.length][];
        for (int i = 0; i < longOnes.length; i++) {
            int m = longOnes[i];
            BitSet startDays = new BitSet(days);
            for (int s : p.allowedStarts(m)) startDays.set(s / periods);
            List<Integer> places = new ArrayList<>();
            for (int r : p.suitableRooms(m))
                for (int d = startDays.nextSetBit(0); d >= 0; d = startDays.nextSetBit(d + 1))
                    for (int j = 0; j < perDay; j++) places.add((r * days + d) * perDay + j);
            adj[i] = places.stream().mapToInt(Integer::intValue).toArray();
        }
        BipartiteMatching matching = new BipartiteMatching(adj, p.roomCount() * days * perDay);
        if (matching.isLeftPerfect()) return;

        BipartiteMatching.Deficiency d = matching.deficiency();
        TreeSet<String> roomNames = new TreeSet<>();
        for (int place : d.right()) roomNames.add(p.room(place / perDay / days).code());
        int[] stuck = Arrays.stream(d.left()).map(i -> longOnes[i]).toArray();
        reasons.add(String.format("Not enough room for long sessions: %d sessions of two or more periods can only use rooms %s, "
                        + "which fit at most %d such sessions a week (%d per room per day in a %d-period day): %s.",
                stuck.length, roomNames, d.right().length, perDay, periods, labels(p, stuck)));
    }

    /** A teacher with a daily limit can teach at most min(limit, available periods) on each day. */
    private static void checkDailyLimits(Problem p, List<String> reasons) {
        SchedulingConfig config = p.config();
        for (int f = 0; f < p.faculty().length; f++) {
            Faculty fac = p.faculty()[f];
            if (!fac.hasDailyLimit()) continue;
            int needed = Arrays.stream(p.meetingsOfFaculty(f)).map(p::durationOf).sum();
            int capacity = 0;
            for (int d = 0; d < config.days(); d++) {
                int day = d;
                long free = IntStream.range(0, config.periodsPerDay())
                        .filter(t -> !fac.unavailable().contains(new TimeSlot(day, t))).count();
                capacity += (int) Math.min(fac.maxPerDay(), free);
            }
            if (needed > capacity) {
                reasons.add(String.format("%s teaches %d periods a week, but with a limit of %d per day and their availability can teach at most %d.",
                        fac.name(), needed, fac.maxPerDay(), capacity));
            }
        }
    }

    private static int[] meetingsOf(Problem p, int[] pieces) {
        return Arrays.stream(pieces).map(p::pieceMeeting).distinct().sorted().toArray();
    }

    private static String describe(Problem p, int[] meetings) {
        if (meetings.length == 0) return "empty";
        Map<String, Integer> byGroup = new LinkedHashMap<>();
        Map<String, Integer> byFaculty = new LinkedHashMap<>();
        for (int m : meetings) {
            for (int g : p.groupsOf(m)) byGroup.merge(p.groups()[g].name(), 1, Integer::sum);
            byFaculty.merge(p.meeting(m).faculty().name(), 1, Integer::sum);
        }
        // A student group (or teacher) present at every meeting of the clique explains it on its own.
        for (Map.Entry<String, Integer> e : byGroup.entrySet())
            if (e.getValue() == meetings.length) return "all attended by " + e.getKey();
        if (byFaculty.size() == 1) return "all taught by " + byFaculty.keySet().iterator().next();
        return "no single batch or teacher is in all of them; meetings per batch " + byGroup;
    }

    private static String labels(Problem p, int[] meetings) {
        String shown = Arrays.stream(meetings).limit(LIST_LIMIT).mapToObj(m -> p.meeting(m).label()).collect(Collectors.joining("; "));
        return meetings.length > LIST_LIMIT ? shown + "; ... (" + (meetings.length - LIST_LIMIT) + " more)" : shown;
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
