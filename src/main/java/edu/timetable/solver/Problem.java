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
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * A dataset compiled into the graph-colouring form the solvers work on.
 *
 * <ul>
 *   <li>Every course is expanded into {@code meetingsPerWeek} meetings (vertices). A meeting
 *       lasts {@code periodsPerMeeting} consecutive periods on one day, in one room, and never
 *       runs through the lunch break.</li>
 *   <li><b>Student groups</b> are the units that cannot be in two places at once: every batch
 *       that is not split, and every lab group of a split batch. A class for a split batch is
 *       attended by all of its groups; a class for one group by that group alone.</li>
 *   <li>Two meetings conflict (edge) when they share a student group or a faculty member, so
 *       their periods must not overlap. With single-period meetings this is plain graph
 *       colouring with slots as colours.</li>
 *   <li>Each meeting has the start slots it may use (all its periods on one day, its teacher
 *       available for each) and the rooms that suit it (type, seats).</li>
 * </ul>
 * Room clashes and teachers' daily limits are not edges; the solvers enforce them separately.
 *
 * <p>For the infeasibility bounds, a meeting of length d is also seen as d unit
 * <b>pieces</b>. Pieces of conflicting meetings, and pieces of one meeting, need distinct
 * slots, so cliques and matchings over pieces give sound bounds for meetings of any length.
 */
public final class Problem {
    private final Dataset dataset;
    private final SchedulingConfig config;
    private final Room[] rooms;
    private final Meeting[] meetings;
    private final Batch[] batches;
    private final Batch[] groups;
    private final Faculty[] faculty;
    private final Course[] courses;

    private final int[][] groupsOf;
    private final int[] facultyOf;
    private final int[] courseOf;
    private final int[] durationOf;
    private final int[] buildingOf;

    private final int[][] allowedStarts;
    private final int[][] suitableRooms;
    private final BitSet[] neighbourSet;
    private final int[][] neighbours;
    private final int[][] meetingsOfGroup;
    private final int[][] meetingsOfFaculty;
    private final int[][] meetingsOfCourse;

    private final int[] pieceMeeting;
    private final int[] pieceOffset;
    private final BitSet[] pieceNeighbourSet;

    public Problem(Dataset dataset) {
        this.dataset = dataset;
        this.config = dataset.config();
        int periods = config.periodsPerDay();
        // Rooms sorted by capacity so that iterating them in order is a best-fit choice.
        this.rooms = dataset.rooms().stream()
                .sorted(Comparator.comparingInt(Room::capacity).thenComparing(Room::code))
                .toArray(Room[]::new);
        this.batches = dataset.batches().toArray(Batch[]::new);
        this.faculty = dataset.faculty().toArray(Faculty[]::new);
        this.courses = dataset.courses().toArray(Course[]::new);

        // Student groups: batches without lab groups, and the lab groups themselves.
        Map<Integer, Batch> batchById = new HashMap<>();
        for (Batch b : batches) batchById.put(b.id(), b);
        Map<Integer, List<Batch>> children = new HashMap<>();
        for (Batch b : batches) {
            if (!b.isGroup()) continue;
            Batch parent = batchById.get(b.parentId());
            if (parent == null) throw new IllegalArgumentException("Lab group " + b.name() + " refers to unknown batch id " + b.parentId());
            if (parent.isGroup()) throw new IllegalArgumentException("Lab group " + b.name() + " belongs to " + parent.name() + ", which is itself a lab group");
            children.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(b);
        }
        this.groups = IntStream.range(0, batches.length).mapToObj(i -> batches[i])
                .filter(b -> !children.containsKey(b.id())).toArray(Batch[]::new);
        Map<Integer, Integer> groupIdx = indexOf(groups.length, i -> groups[i].id());
        Map<Integer, Integer> facultyIdx = indexOf(faculty.length, i -> faculty[i].id());

        List<Meeting> ms = new ArrayList<>();
        List<int[]> gOf = new ArrayList<>();
        List<Integer> fOf = new ArrayList<>(), cOf = new ArrayList<>();
        for (int c = 0; c < courses.length; c++) {
            Course course = courses[c];
            List<Batch> attending = new ArrayList<>();
            for (int id : course.batchIds()) {
                Batch b = batchById.get(id);
                if (b == null) throw new IllegalArgumentException("Course " + course.code() + " refers to unknown batch id " + id);
                attending.add(b);
            }
            // A lab group listed together with its own parent batch is already covered by it.
            attending.removeIf(b -> b.isGroup() && course.batchIds().contains(b.parentId()));
            TreeSet<Integer> attendingGroups = new TreeSet<>();
            for (Batch b : attending) {
                for (Batch g : children.getOrDefault(b.id(), List.of(b))) attendingGroups.add(groupIdx.get(g.id()));
            }
            int[] g = attendingGroups.stream().mapToInt(Integer::intValue).toArray();
            Integer f = facultyIdx.get(course.facultyId());
            if (f == null) throw new IllegalArgumentException("Course " + course.code() + " refers to unknown faculty id " + course.facultyId());
            for (int k = 0; k < course.meetingsPerWeek(); k++) {
                ms.add(new Meeting(ms.size(), course, k, attending, faculty[f]));
                gOf.add(g);
                fOf.add(f);
                cOf.add(c);
            }
        }
        this.meetings = ms.toArray(Meeting[]::new);
        int m = meetings.length;
        this.groupsOf = gOf.toArray(int[][]::new);
        this.facultyOf = fOf.stream().mapToInt(Integer::intValue).toArray();
        this.courseOf = cOf.stream().mapToInt(Integer::intValue).toArray();
        this.durationOf = new int[m];
        for (int i = 0; i < m; i++) durationOf[i] = meetings[i].course().periodsPerMeeting();

        this.buildingOf = new int[rooms.length];
        Map<String, Integer> buildingIds = new HashMap<>();
        for (int r = 0; r < rooms.length; r++) {
            buildingOf[r] = buildingIds.computeIfAbsent(rooms[r].building(), k -> buildingIds.size());
        }

        int slots = config.slotCount();
        this.allowedStarts = new int[m][];
        this.suitableRooms = new int[m][];
        for (int i = 0; i < m; i++) {
            Meeting mt = meetings[i];
            int d = durationOf[i];
            allowedStarts[i] = IntStream.range(0, slots)
                    .filter(s -> s % periods + d <= periods)
                    .filter(s -> !config.crossesLunch(s % periods, d))
                    .filter(s -> IntStream.range(s, s + d).noneMatch(t ->
                            mt.faculty().unavailable().contains(TimeSlot.fromIndex(t, periods))))
                    .toArray();
            suitableRooms[i] = IntStream.range(0, rooms.length).filter(r -> roomFits(mt, rooms[r])).toArray();
        }

        this.meetingsOfGroup = group(groups.length, groupsOf);
        this.meetingsOfFaculty = group(faculty.length, single(facultyOf));
        this.meetingsOfCourse = group(courses.length, single(courseOf));

        this.neighbourSet = new BitSet[m];
        for (int i = 0; i < m; i++) neighbourSet[i] = new BitSet(m);
        for (int[] members : meetingsOfGroup) connectAll(members);
        for (int[] members : meetingsOfFaculty) connectAll(members);
        this.neighbours = new int[m][];
        for (int i = 0; i < m; i++) neighbours[i] = neighbourSet[i].stream().toArray();

        int pieces = IntStream.of(durationOf).sum();
        this.pieceMeeting = new int[pieces];
        this.pieceOffset = new int[pieces];
        int[] firstPiece = new int[m];
        for (int i = 0, p = 0; i < m; i++) {
            firstPiece[i] = p;
            for (int k = 0; k < durationOf[i]; k++, p++) {
                pieceMeeting[p] = i;
                pieceOffset[p] = k;
            }
        }
        this.pieceNeighbourSet = new BitSet[pieces];
        for (int p = 0; p < pieces; p++) {
            int i = pieceMeeting[p];
            BitSet adj = new BitSet(pieces);
            adj.set(firstPiece[i], firstPiece[i] + durationOf[i]);
            for (int n : neighbours[i]) adj.set(firstPiece[n], firstPiece[n] + durationOf[n]);
            adj.clear(p);
            pieceNeighbourSet[p] = adj;
        }
    }

    static boolean roomFits(Meeting m, Room r) {
        return r.type() == m.course().roomType() && r.capacity() >= m.studentCount();
    }

    private void connectAll(int[] members) {
        for (int a : members)
            for (int b : members)
                if (a != b) neighbourSet[a].set(b);
    }

    private static Map<Integer, Integer> indexOf(int n, IntUnaryOperator id) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < n; i++) map.put(id.applyAsInt(i), i);
        return map;
    }

    /** Inverts an owner relation: which items (meetings) belong to each group. */
    private static int[][] group(int count, int[][] owners) {
        List<List<Integer>> lists = new ArrayList<>();
        for (int g = 0; g < count; g++) lists.add(new ArrayList<>());
        for (int i = 0; i < owners.length; i++)
            for (int g : owners[i]) lists.get(g).add(i);
        int[][] out = new int[count][];
        for (int g = 0; g < count; g++) out[g] = lists.get(g).stream().mapToInt(Integer::intValue).toArray();
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

    /** Do meeting a starting at sa and meeting b starting at sb share a period? */
    public boolean overlap(int a, int sa, int b, int sb) {
        return sa < sb + durationOf[b] && sb < sa + durationOf[a];
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
    /** Student groups: unsplit batches and lab groups, the units that cannot be double-booked. */
    public Batch[] groups() { return groups; }
    public Faculty[] faculty() { return faculty; }
    public Course[] courses() { return courses; }
    /** Dense indices of the student groups attending the meeting. */
    public int[] groupsOf(int meeting) { return groupsOf[meeting]; }
    public int facultyOf(int meeting) { return facultyOf[meeting]; }
    public int courseOf(int meeting) { return courseOf[meeting]; }
    /** Number of consecutive periods the meeting lasts. */
    public int durationOf(int meeting) { return durationOf[meeting]; }
    public int buildingOf(int room) { return buildingOf[room]; }
    /** Slots the meeting may start in: all its periods on one day, its teacher available for each. */
    public int[] allowedStarts(int meeting) { return allowedStarts[meeting]; }
    /** Rooms of the right type and size for the meeting, smallest first. */
    public int[] suitableRooms(int meeting) { return suitableRooms[meeting]; }
    public boolean hasOptions(int meeting) { return allowedStarts[meeting].length > 0 && suitableRooms[meeting].length > 0; }
    public BitSet neighbourSet(int meeting) { return neighbourSet[meeting]; }
    public int[] neighbours(int meeting) { return neighbours[meeting]; }
    public boolean conflicts(int a, int b) { return neighbourSet[a].get(b); }
    public int[] meetingsOfGroup(int g) { return meetingsOfGroup[g]; }
    public int[] meetingsOfFaculty(int f) { return meetingsOfFaculty[f]; }
    public int[] meetingsOfCourse(int c) { return meetingsOfCourse[c]; }
    public int pieceCount() { return pieceMeeting.length; }
    public int pieceMeeting(int piece) { return pieceMeeting[piece]; }
    public int pieceOffset(int piece) { return pieceOffset[piece]; }
    public BitSet pieceNeighbourSet(int piece) { return pieceNeighbourSet[piece]; }

    public long edgeCount() {
        long sum = 0;
        for (int[] n : neighbours) sum += n.length;
        return sum / 2;
    }
}
