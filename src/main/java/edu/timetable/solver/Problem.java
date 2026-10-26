package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;
import edu.timetable.model.TimetableEntry;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * A dataset compiled into the graph-colouring form the solvers work on.
 *
 * <ul>
 *   <li>Every course is expanded into {@code meetingsPerWeek} meetings (vertices).</li>
 *   <li>Two meetings conflict (edge) when they share a batch or a faculty member, so they
 *       must receive different time slots (colours). Combined classes, attended by several
 *       batches, conflict with the meetings of every one of them.</li>
 *   <li>Each meeting has a base domain of (slot, room) pairs, encoded as bit
 *       {@code slot * roomCount + room}, already filtered by the unary hard constraints:
 *       room type, room capacity and faculty unavailability.</li>
 * </ul>
 * Room clashes (two meetings in one room at one time) are the only binary constraint that is
 * not an edge; the solvers enforce it separately.
 */
public final class Problem {
    private final Dataset dataset;
    private final SchedulingConfig config;
    private final Room[] rooms;
    private final Meeting[] meetings;
    private final Batch[] batches;
    private final Faculty[] faculty;
    private final Course[] courses;

    private final int[][] batchesOf;
    private final int[] facultyOf;
    private final int[] courseOf;
    private final int[] buildingOf;

    private final BitSet[] baseDomain;
    private final int[][] allowedSlots;
    private final int[][] suitableRooms;
    private final BitSet[] neighbourSet;
    private final int[][] neighbours;
    private final int[][] meetingsOfBatch;
    private final int[][] meetingsOfFaculty;
    private final int[][] meetingsOfCourse;

    public Problem(Dataset dataset) {
        this.dataset = dataset;
        this.config = dataset.config();
        // Rooms sorted by capacity so that iterating them in order is a best-fit choice.
        this.rooms = dataset.rooms().stream()
                .sorted(Comparator.comparingInt(Room::capacity).thenComparing(Room::code))
                .toArray(Room[]::new);
        this.batches = dataset.batches().toArray(Batch[]::new);
        this.faculty = dataset.faculty().toArray(Faculty[]::new);
        this.courses = dataset.courses().toArray(Course[]::new);

        Map<Integer, Integer> batchIdx = indexOf(batches.length, i -> batches[i].id());
        Map<Integer, Integer> facultyIdx = indexOf(faculty.length, i -> faculty[i].id());

        List<Meeting> ms = new ArrayList<>();
        List<int[]> bOf = new ArrayList<>();
        List<Integer> fOf = new ArrayList<>(), cOf = new ArrayList<>();
        for (int c = 0; c < courses.length; c++) {
            Course course = courses[c];
            int[] b = new int[course.batchIds().size()];
            List<Batch> attending = new ArrayList<>();
            for (int k = 0; k < b.length; k++) {
                Integer idx = batchIdx.get(course.batchIds().get(k));
                if (idx == null) throw new IllegalArgumentException("Course " + course.code() + " refers to unknown batch id " + course.batchIds().get(k));
                b[k] = idx;
                attending.add(batches[idx]);
            }
            Integer f = facultyIdx.get(course.facultyId());
            if (f == null) throw new IllegalArgumentException("Course " + course.code() + " refers to unknown faculty id " + course.facultyId());
            for (int k = 0; k < course.meetingsPerWeek(); k++) {
                ms.add(new Meeting(ms.size(), course, k, attending, faculty[f]));
                bOf.add(b);
                fOf.add(f);
                cOf.add(c);
            }
        }
        this.meetings = ms.toArray(Meeting[]::new);
        int m = meetings.length;
        this.batchesOf = bOf.toArray(int[][]::new);
        this.facultyOf = fOf.stream().mapToInt(Integer::intValue).toArray();
        this.courseOf = cOf.stream().mapToInt(Integer::intValue).toArray();

        this.buildingOf = new int[rooms.length];
        Map<String, Integer> buildingIds = new HashMap<>();
        for (int r = 0; r < rooms.length; r++) {
            buildingOf[r] = buildingIds.computeIfAbsent(rooms[r].building(), k -> buildingIds.size());
        }

        // The base domain is a product: slots the faculty member can teach x rooms that suit.
        int slots = config.slotCount();
        this.baseDomain = new BitSet[m];
        this.allowedSlots = new int[m][];
        this.suitableRooms = new int[m][];
        for (int i = 0; i < m; i++) {
            Meeting mt = meetings[i];
            allowedSlots[i] = IntStream.range(0, slots)
                    .filter(s -> !mt.faculty().unavailable().contains(TimeSlot.fromIndex(s, config.periodsPerDay())))
                    .toArray();
            suitableRooms[i] = IntStream.range(0, rooms.length)
                    .filter(r -> roomFits(mt, rooms[r]))
                    .toArray();
            BitSet d = new BitSet(slots * rooms.length);
            for (int s : allowedSlots[i])
                for (int r : suitableRooms[i]) d.set(pair(s, r));
            baseDomain[i] = d;
        }

        this.meetingsOfBatch = group(batches.length, batchesOf);
        this.meetingsOfFaculty = group(faculty.length, single(facultyOf));
        this.meetingsOfCourse = group(courses.length, single(courseOf));

        this.neighbourSet = new BitSet[m];
        for (int i = 0; i < m; i++) neighbourSet[i] = new BitSet(m);
        for (int[] group : meetingsOfBatch) connectAll(group);
        for (int[] group : meetingsOfFaculty) connectAll(group);
        this.neighbours = new int[m][];
        for (int i = 0; i < m; i++) neighbours[i] = neighbourSet[i].stream().toArray();
    }

    static boolean roomFits(Meeting m, Room r) {
        return r.type() == m.course().roomType() && r.capacity() >= m.studentCount();
    }

    private void connectAll(int[] group) {
        for (int a : group)
            for (int b : group)
                if (a != b) neighbourSet[a].set(b);
    }

    private static Map<Integer, Integer> indexOf(int n, IntUnaryOperator id) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < n; i++) map.put(id.applyAsInt(i), i);
        return map;
    }

    /** Inverts an owner relation: which items (meetings) belong to each group. */
    private static int[][] group(int groups, int[][] owners) {
        List<List<Integer>> lists = new ArrayList<>();
        for (int g = 0; g < groups; g++) lists.add(new ArrayList<>());
        for (int i = 0; i < owners.length; i++)
            for (int g : owners[i]) lists.get(g).add(i);
        int[][] out = new int[groups][];
        for (int g = 0; g < groups; g++) out[g] = lists.get(g).stream().mapToInt(Integer::intValue).toArray();
        return out;
    }

    private static int[][] single(int[] owner) {
        int[][] out = new int[owner.length][];
        for (int i = 0; i < owner.length; i++) out[i] = new int[]{owner[i]};
        return out;
    }

    // ---- encoding helpers -------------------------------------------------------------

    public int pair(int slot, int room) {
        return slot * rooms.length + room;
    }

    public int slotOfPair(int pair) {
        return pair / rooms.length;
    }

    public int roomOfPair(int pair) {
        return pair % rooms.length;
    }

    public int dayOf(int slot) {
        return slot / config.periodsPerDay();
    }

    public int periodOf(int slot) {
        return slot % config.periodsPerDay();
    }

    /** All distinct slots that still have at least one room in {@code domain}. */
    public BitSet slotsOf(BitSet domain) {
        BitSet out = new BitSet(slotCount());
        for (int p = domain.nextSetBit(0); p >= 0; p = domain.nextSetBit(p + 1)) out.set(slotOfPair(p));
        return out;
    }

    // ---- conversion to and from persisted entries ----------------------------------------

    public List<TimetableEntry> toEntries(Schedule schedule) {
        List<TimetableEntry> out = new ArrayList<>(meetings.length);
        for (int i = 0; i < meetings.length; i++) {
            int s = schedule.slotOf(i);
            if (s < 0) continue;
            out.add(new TimetableEntry(meetings[i].course().id(), meetings[i].ordinal(),
                    dayOf(s), periodOf(s), rooms[schedule.roomOf(i)].id()));
        }
        return out;
    }

    /** Rebuilds a schedule from saved entries; entries that no longer match the data are skipped. */
    public Schedule fromEntries(List<TimetableEntry> entries) {
        Map<String, Integer> meetingByKey = new HashMap<>();
        for (Meeting mt : meetings) meetingByKey.put(mt.course().id() + ":" + mt.ordinal(), mt.index());
        Map<Integer, Integer> roomIdx = indexOf(rooms.length, i -> rooms[i].id());
        Schedule s = new Schedule(meetings.length);
        for (TimetableEntry e : entries) {
            Integer mi = meetingByKey.get(e.courseId() + ":" + e.meetingIndex());
            Integer ri = roomIdx.get(e.roomId());
            if (mi == null || ri == null || e.day() >= config.days() || e.period() >= config.periodsPerDay()) continue;
            s.assign(mi, e.day() * config.periodsPerDay() + e.period(), ri);
        }
        return s;
    }

    // ---- accessors ----------------------------------------------------------------------

    public Dataset dataset() { return dataset; }
    public SchedulingConfig config() { return config; }
    public int slotCount() { return config.slotCount(); }
    public int roomCount() { return rooms.length; }
    public int meetingCount() { return meetings.length; }
    public Room[] rooms() { return rooms; }
    public Room room(int r) { return rooms[r]; }
    public Meeting meeting(int i) { return meetings[i]; }
    public Batch[] batches() { return batches; }
    public Faculty[] faculty() { return faculty; }
    public Course[] courses() { return courses; }
    /** Dense indices of the batches attending the meeting (several for a combined class). */
    public int[] batchesOf(int meeting) { return batchesOf[meeting]; }
    public int facultyOf(int meeting) { return facultyOf[meeting]; }
    public int courseOf(int meeting) { return courseOf[meeting]; }
    public int buildingOf(int room) { return buildingOf[room]; }
    public BitSet baseDomain(int meeting) { return baseDomain[meeting]; }
    /** Slots the meeting's faculty member can teach in, ascending. */
    public int[] allowedSlots(int meeting) { return allowedSlots[meeting]; }
    /** Rooms of the right type and size for the meeting, smallest first. */
    public int[] suitableRooms(int meeting) { return suitableRooms[meeting]; }
    public BitSet neighbourSet(int meeting) { return neighbourSet[meeting]; }
    public int[] neighbours(int meeting) { return neighbours[meeting]; }
    public boolean conflicts(int a, int b) { return neighbourSet[a].get(b); }
    public int[] meetingsOfBatch(int b) { return meetingsOfBatch[b]; }
    public int[] meetingsOfFaculty(int f) { return meetingsOfFaculty[f]; }
    public int[] meetingsOfCourse(int c) { return meetingsOfCourse[c]; }

    public long edgeCount() {
        long sum = 0;
        for (int[] n : neighbours) sum += n.length;
        return sum / 2;
    }
}
