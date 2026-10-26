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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Random instances small enough to decide by exhaustive enumeration, and that enumeration. */
final class TinyInstances {
    private TinyInstances() {
    }

    static Dataset random(long seed) {
        Random rnd = new Random(seed);
        SchedulingConfig config = new SchedulingConfig(1, 4 + rnd.nextInt(2));
        List<Room> rooms = new ArrayList<>();
        int roomCount = 1 + rnd.nextInt(3);
        for (int r = 0; r < roomCount; r++) {
            rooms.add(new Room(r + 1, "R" + r, rnd.nextBoolean() ? "A" : "B", rnd.nextInt(4) == 0 ? 30 : rnd.nextBoolean() ? 60 : 120,
                    rnd.nextInt(4) == 0 ? RoomType.LAB : RoomType.LECTURE));
        }
        List<Batch> batches = new ArrayList<>();
        int batchCount = 2 + rnd.nextInt(2);
        for (int b = 0; b < batchCount; b++) batches.add(new Batch(b + 1, "B" + b, rnd.nextBoolean() ? 25 : 50));
        List<Faculty> faculty = new ArrayList<>();
        int facultyCount = 2 + rnd.nextInt(2);
        for (int f = 0; f < facultyCount; f++) {
            Set<TimeSlot> off = new HashSet<>();
            for (int p = 0; p < config.periodsPerDay(); p++) if (rnd.nextInt(5) == 0) off.add(new TimeSlot(0, p));
            faculty.add(new Faculty(f + 1, "F" + f, off));
        }
        List<Course> courses = new ArrayList<>();
        int courseCount = 3 + rnd.nextInt(3);
        int meetings = 0;
        for (int c = 0; c < courseCount && meetings < 7; c++) {
            int k = Math.min(1 + rnd.nextInt(2), 7 - meetings);
            meetings += k;
            // Every fourth course or so is a combined class for two batches.
            List<Integer> attending = new ArrayList<>(List.of(1 + rnd.nextInt(batchCount)));
            if (rnd.nextInt(4) == 0) attending.add(1 + rnd.nextInt(batchCount));
            courses.add(new Course(c + 1, "C" + c, "Course " + c, 1 + rnd.nextInt(facultyCount), attending,
                    k, rnd.nextInt(8) == 0 ? RoomType.LAB : RoomType.LECTURE));
        }
        return new Dataset(config, rooms, faculty, batches, courses);
    }

    /** Exhaustive search over every (slot, room) for every meeting, checking clashes directly. */
    static boolean bruteForceFeasible(Problem p) {
        return place(p, new Schedule(p.meetingCount()), 0);
    }

    private static boolean place(Problem p, Schedule s, int m) {
        if (m == p.meetingCount()) return HardConstraintValidator.isFeasible(p, s);
        Meeting mt = p.meeting(m);
        for (int slot = 0; slot < p.slotCount(); slot++) {
            for (int r = 0; r < p.roomCount(); r++) {
                Room room = p.room(r);
                int students = mt.batches().stream().mapToInt(Batch::studentCount).sum();
                if (room.type() != mt.course().roomType() || room.capacity() < students) continue;
                if (mt.faculty().unavailable().contains(TimeSlot.fromIndex(slot, p.config().periodsPerDay()))) continue;
                boolean clash = false;
                for (int o = 0; o < m && !clash; o++) {
                    if (s.slotOf(o) != slot) continue;
                    Meeting other = p.meeting(o);
                    boolean sharedBatch = other.batches().stream().anyMatch(mt.batches()::contains);
                    clash = s.roomOf(o) == r || sharedBatch || other.faculty().id() == mt.faculty().id();
                }
                if (clash) continue;
                s.assign(m, slot, r);
                if (place(p, s, m + 1)) return true;
                s.assign(m, -1, -1);
            }
        }
        return false;
    }
}
