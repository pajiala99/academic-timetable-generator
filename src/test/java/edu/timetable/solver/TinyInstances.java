package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Random instances small enough to decide by exhaustive enumeration, and that enumeration.
 * The instances mix single- and double-period meetings, combined classes, lab groups and
 * teachers' daily limits. The enumerator works from the dataset alone, sharing no code with
 * the solver.
 */
final class TinyInstances {
    private TinyInstances() {
    }

    static Dataset random(long seed) {
        Random rnd = new Random(seed);
        SchedulingConfig config = rnd.nextBoolean() ? new SchedulingConfig(1, 4 + rnd.nextInt(2)) : new SchedulingConfig(2, 3);
        List<Room> rooms = new ArrayList<>();
        int roomCount = 2 + rnd.nextInt(2);
        for (int r = 0; r < roomCount; r++) {
            rooms.add(new Room(r + 1, "R" + r, rnd.nextBoolean() ? "A" : "B", rnd.nextInt(4) == 0 ? 30 : rnd.nextBoolean() ? 60 : 120,
                    rnd.nextInt(4) == 0 ? RoomType.LAB : RoomType.LECTURE));
        }
        List<Batch> batches = new ArrayList<>();
        int batchCount = 2 + rnd.nextInt(2);
        for (int b = 0; b < batchCount; b++) batches.add(new Batch(b + 1, "B" + b, rnd.nextBoolean() ? 25 : 50));
        if (rnd.nextInt(3) == 0) { // split the first batch into two lab groups
            batches.add(new Batch(10, "B0-G1", 20, 1));
            batches.add(new Batch(11, "B0-G2", 20, 1));
        }
        List<Faculty> faculty = new ArrayList<>();
        int facultyCount = 2 + rnd.nextInt(2);
        for (int f = 0; f < facultyCount; f++) {
            Set<TimeSlot> off = new HashSet<>();
            for (int d = 0; d < config.days(); d++)
                for (int p = 0; p < config.periodsPerDay(); p++) if (rnd.nextInt(8) == 0) off.add(new TimeSlot(d, p));
            int limit = rnd.nextInt(3) == 0 ? 2 + rnd.nextInt(2) : 0;
            faculty.add(new Faculty(f + 1, "F" + f, off, limit));
        }
        List<Course> courses = new ArrayList<>();
        int courseCount = 3 + rnd.nextInt(3);
        int periods = 0;
        for (int c = 0; c < courseCount && periods < 7; c++) {
            int length = rnd.nextInt(4) == 0 ? 2 : 1;
            int meetings = Math.max(1, Math.min(1 + rnd.nextInt(2), (7 - periods) / length));
            if (meetings * length > 7 - periods) break;
            periods += meetings * length;
            List<Integer> attending = new ArrayList<>(List.of(batches.get(rnd.nextInt(batches.size())).id()));
            if (rnd.nextInt(4) == 0) attending.add(batches.get(rnd.nextInt(batches.size())).id());
            courses.add(new Course(c + 1, "C" + c, "Course " + c, 1 + rnd.nextInt(facultyCount), attending,
                    meetings, length, rnd.nextInt(8) == 0 ? RoomType.LAB : RoomType.LECTURE));
        }
        return new Dataset(config, rooms, faculty, batches, courses);
    }

    /** One meeting, as the enumerator sees it. */
    private record Item(Course course, Set<Integer> groups, int students, Faculty teacher) {
    }

    /** Exhaustive search over every start and room for every meeting; returns a timetable or null. */
    static Schedule bruteForce(Problem p) {
        Dataset d = p.dataset();
        Map<Integer, Batch> byId = new HashMap<>();
        Map<Integer, Set<Integer>> groupsOf = new HashMap<>();
        for (Batch b : d.batches()) byId.put(b.id(), b);
        for (Batch b : d.batches()) if (b.isGroup()) groupsOf.computeIfAbsent(b.parentId(), k -> new HashSet<>()).add(b.id());
        Map<Integer, Faculty> teachers = d.facultyById();
        // Meetings in the same order as the Problem numbers them: course by course.
        List<Item> items = new ArrayList<>();
        for (Course c : d.courses()) {
            Set<Integer> groups = new HashSet<>();
            int students = 0;
            for (int id : c.batchIds()) {
                Batch b = byId.get(id);
                if (b.isGroup() && c.batchIds().contains(b.parentId())) continue; // covered by its batch
                students += b.studentCount();
                groups.addAll(groupsOf.getOrDefault(id, Set.of(id)));
            }
            for (int k = 0; k < c.meetingsPerWeek(); k++) items.add(new Item(c, groups, students, teachers.get(c.facultyId())));
        }
        Schedule s = new Schedule(items.size());
        return place(p, items, s, 0, new HashSet<>(), new HashMap<>()) ? s : null;
    }

    static boolean bruteForceFeasible(Problem p) {
        return bruteForce(p) != null;
    }

    private static boolean place(Problem p, List<Item> items, Schedule s, int i, Set<String> busy, Map<String, Integer> load) {
        if (i == items.size()) return true;
        Item it = items.get(i);
        int periods = p.config().periodsPerDay(), length = it.course().periodsPerMeeting();
        for (int start = 0; start < p.slotCount(); start++) {
            if (start % periods + length > periods) continue;
            String dayKey = it.teacher().id() + "@" + start / periods;
            int already = load.getOrDefault(dayKey, 0);
            if (it.teacher().hasDailyLimit() && already + length > it.teacher().maxPerDay()) continue;
            for (int r = 0; r < p.roomCount(); r++) {
                Room room = p.room(r);
                if (room.type() != it.course().roomType() || room.capacity() < it.students()) continue;
                List<String> keys = new ArrayList<>();
                boolean ok = true;
                for (int t = start; t < start + length && ok; t++) {
                    if (it.teacher().unavailable().contains(TimeSlot.fromIndex(t, periods))) ok = false;
                    keys.add("room " + room.id() + "@" + t);
                    keys.add("teacher " + it.teacher().id() + "@" + t);
                    for (int g : it.groups()) keys.add("group " + g + "@" + t);
                }
                if (!ok || keys.stream().anyMatch(busy::contains)) continue;
                busy.addAll(keys);
                load.put(dayKey, already + length);
                s.assign(i, start, r);
                if (place(p, items, s, i + 1, busy, load)) return true;
                s.assign(i, -1, -1);
                load.put(dayKey, already);
                busy.removeAll(keys);
            }
        }
        return false;
    }
}
