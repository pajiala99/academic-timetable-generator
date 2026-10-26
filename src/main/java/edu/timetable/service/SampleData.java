package edu.timetable.service;

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

import static edu.timetable.model.RoomType.LAB;
import static edu.timetable.model.RoomType.LECTURE;

/** Ready-made datasets: a realistic department, an over-subscribed variant, and random instances. */
public final class SampleData {
    private SampleData() {
    }

    /**
     * A Computer Science department: 6 batches, 16 faculty, 11 rooms in 2 buildings, a 5 x 7
     * week, and 45 courses (136 weekly meetings) of which three are combined classes that two
     * sections attend together in the seminar hall.
     */
    public static Dataset department() {
        SchedulingConfig config = new SchedulingConfig(5, 7);
        List<Room> rooms = List.of(
                new Room(1, "LH-101", "Main Block", 70, LECTURE),
                new Room(2, "LH-102", "Main Block", 70, LECTURE),
                new Room(3, "LH-103", "Main Block", 60, LECTURE),
                new Room(4, "LH-104", "Main Block", 60, LECTURE),
                new Room(5, "TB-201", "Tech Block", 65, LECTURE),
                new Room(6, "TB-202", "Tech Block", 65, LECTURE),
                new Room(7, "TB-203", "Tech Block", 55, LECTURE),
                new Room(8, "LAB-1", "Tech Block", 60, LAB),
                new Room(9, "LAB-2", "Tech Block", 60, LAB),
                new Room(10, "LAB-3", "Main Block", 64, LAB),
                new Room(11, "SH-01", "Main Block", 150, LECTURE));
        List<Batch> batches = List.of(
                new Batch(1, "CSE-1A", 60), new Batch(2, "CSE-1B", 60),
                new Batch(3, "CSE-2A", 60), new Batch(4, "CSE-2B", 55),
                new Batch(5, "CSE-3A", 58), new Batch(6, "CSE-3B", 52));
        List<Faculty> faculty = List.of(
                new Faculty(1, "Dr. Anita Rao", wholeDay(4, config)),             // Fridays: dean's office
                new Faculty(2, "Dr. Vikram Mehta"),
                new Faculty(3, "Prof. Sunita Kulkarni"),
                new Faculty(4, "Dr. Rahul Verma"),
                new Faculty(5, "Dr. Neha Joshi", Set.of(new TimeSlot(0, 0), new TimeSlot(2, 0))),
                new Faculty(6, "Ms. Priya Nair"),
                new Faculty(7, "Dr. Arjun Singh"),
                new Faculty(8, "Prof. Kavita Desai"),
                new Faculty(9, "Dr. Sanjay Gupta"),
                new Faculty(10, "Dr. Meera Pillai"),
                new Faculty(11, "Mr. Karan Malhotra"),
                new Faculty(12, "Prof. Deepak Reddy", periods(config, 0, 0, 1, 2)), // Monday mornings
                new Faculty(13, "Dr. Lakshmi Menon"),
                new Faculty(14, "Dr. Amit Banerjee", periods(config, 3, 4, 5, 6)), // Thursday afternoons
                new Faculty(15, "Dr. Farah Khan"),
                new Faculty(16, "Prof. Rohan Das"));

        List<Course> courses = new ArrayList<>();
        // Year 1
        for (int b : new int[]{1, 2}) {
            add(courses, "MA101", "Engineering Mathematics I", 1, b, 4, LECTURE);
            add(courses, "PH101", "Engineering Physics", 2, b, 3, LECTURE);
            add(courses, "CS101", "Programming in C", 3, b, 4, LECTURE);
            add(courses, "EE101", "Basic Electrical Engineering", 5, b, 3, LECTURE);
            add(courses, "HS101", "Communication Skills", 6, b, 2, LECTURE);
            add(courses, "CS101L", "Programming Lab", 4, b, 3, LAB);
            add(courses, "PH101L", "Physics Lab", b == 1 ? 2 : 9, b, 2, LAB);
        }
        // Year 2
        for (int b : new int[]{3, 4}) {
            add(courses, "MA201", "Discrete Mathematics", 7, b, 4, LECTURE);
            add(courses, "CS201", "Data Structures", 8, b, 4, LECTURE);
            add(courses, "CS202", "Digital Logic Design", 9, b, 3, LECTURE);
            add(courses, "CS203", "Object-Oriented Programming", 10, b, 3, LECTURE);
            add(courses, "HS201", "Economics for Engineers", 6, b, 2, LECTURE);
            add(courses, "CS201L", "Data Structures Lab", 11, b, 3, LAB);
            add(courses, "CS203L", "Java Lab", b == 3 ? 4 : 10, b, 2, LAB);
        }
        // Year 3
        for (int b : new int[]{5, 6}) {
            add(courses, "CS301", "Design and Analysis of Algorithms", 12, b, 4, LECTURE);
            add(courses, "CS302", "Database Management Systems", 13, b, 4, LECTURE);
            add(courses, "CS303", "Operating Systems", 14, b, 4, LECTURE);
            add(courses, "CS304", "Computer Networks", 15, b, 3, LECTURE);
            add(courses, "CS305", "Theory of Computation", 16, b, 3, LECTURE);
            add(courses, "CS302L", "DBMS Lab", b == 5 ? 11 : 13, b, 2, LAB);
            add(courses, "CS303L", "Operating Systems Lab", 14, b, 2, LAB);
        }
        // Combined classes: both sections of a year attend together.
        addCombined(courses, "HS102", "Environmental Studies", 6, List.of(1, 2), 2);
        addCombined(courses, "MA202", "Probability and Statistics", 7, List.of(3, 4), 3);
        addCombined(courses, "CS310", "Machine Learning (elective)", 15, List.of(5, 6), 3);
        return new Dataset(config, rooms, faculty, batches, courses);
    }

    /**
     * The department with three extra electives pushed onto CSE-3A: 36 weekly meetings for a
     * 35-slot week. The maximum-clique bound proves this infeasible before any search.
     */
    public static Dataset overloaded() {
        Dataset base = department();
        List<Course> courses = new ArrayList<>(base.courses());
        add(courses, "CS306", "Compiler Design", 16, 5, 4, LECTURE);
        add(courses, "CS307", "Software Engineering", 15, 5, 4, LECTURE);
        add(courses, "CS309", "Cloud Computing", 13, 5, 3, LECTURE);
        return new Dataset(base.config(), base.rooms(), base.faculty(), base.batches(), courses);
    }

    /**
     * A random department for stress tests and benchmarks. Each batch gets roughly
     * {@code meetingsPerBatch} weekly meetings (out of 35 slots), split into courses of 2-5
     * meetings with one lab course; faculty loads are capped at 20 and about a third of the
     * faculty have a few unavailable slots. Rooms are sized for about 90% utilisation of their slots.
     * Depending on the parameters the instance may be easy, hard, or infeasible.
     */
    public static Dataset synthetic(int batchCount, int meetingsPerBatch, long seed) {
        return synthetic(batchCount, meetingsPerBatch, 0.9, seed);
    }

    /** As {@link #synthetic(int, int, long)}, with the share of room-slots that will be in use. */
    public static Dataset synthetic(int batchCount, int meetingsPerBatch, double roomUtilisation, long seed) {
        Random rnd = new Random(seed);
        SchedulingConfig config = new SchedulingConfig(5, 7);

        List<Batch> batches = new ArrayList<>();
        for (int b = 0; b < batchCount; b++) batches.add(new Batch(b + 1, "B" + (b + 1), 40 + rnd.nextInt(21)));

        int facultyCount = Math.max(2, (int) Math.round(batchCount * meetingsPerBatch / 12.0));
        List<Faculty> faculty = new ArrayList<>();
        for (int f = 0; f < facultyCount; f++) {
            Set<TimeSlot> off = new HashSet<>();
            if (rnd.nextDouble() < 0.35) {
                int n = 3 + rnd.nextInt(6);
                for (int k = 0; k < n; k++) off.add(new TimeSlot(rnd.nextInt(config.days()), rnd.nextInt(config.periodsPerDay())));
            }
            faculty.add(new Faculty(f + 1, "F" + (f + 1), off));
        }

        int[] load = new int[facultyCount];
        int lectureMeetings = 0, labMeetings = 0, hallMeetings = 0;
        List<Course> courses = new ArrayList<>();
        int[] electiveMeetings = new int[batchCount];
        // About a third of neighbouring batch pairs share a combined elective.
        for (int b = 0; b + 1 < batchCount; b += 2) {
            if (rnd.nextDouble() >= 0.35) continue;
            int meetings = 2 + rnd.nextInt(2);
            int f = rnd.nextInt(facultyCount);
            load[f] += meetings;
            electiveMeetings[b] = electiveMeetings[b + 1] = meetings;
            hallMeetings += meetings;
            courses.add(new Course(courses.size() + 1, "E" + (b + 1) + "-" + (b + 2), "Elective for B" + (b + 1) + "+B" + (b + 2),
                    f + 1, List.of(b + 1, b + 2), meetings, LECTURE));
        }
        for (Batch b : batches) {
            int remaining = meetingsPerBatch - electiveMeetings[b.id() - 1];
            int k = 0;
            while (remaining > 0) {
                boolean lab = k == 0;
                int meetings = Math.min(remaining, lab ? 2 + rnd.nextInt(2) : 2 + rnd.nextInt(4));
                int f = rnd.nextInt(facultyCount);
                for (int tries = 0; tries < facultyCount && load[f] + meetings > 20; tries++) f = (f + 1) % facultyCount;
                load[f] += meetings;
                k++;
                add(courses, "C" + b.id() + "-" + k, "Course " + k + " of " + b.name(), f + 1, b.id(), meetings, lab ? LAB : LECTURE);
                remaining -= meetings;
                if (lab) labMeetings += meetings;
                else lectureMeetings += meetings;
            }
        }

        // Just enough rooms for the requested utilisation: scarce, but not hopeless.
        List<Room> rooms = new ArrayList<>();
        String[] buildings = {"North", "South", "East"};
        int lectureRooms = (int) Math.ceil(lectureMeetings / (config.slotCount() * roomUtilisation));
        int labRooms = (int) Math.ceil(labMeetings / (config.slotCount() * roomUtilisation));
        for (int i = 0; i < lectureRooms; i++) {
            int capacity = i % 4 == 3 ? 50 : 60 + 10 * rnd.nextInt(2);
            rooms.add(new Room(rooms.size() + 1, "R" + (101 + i), buildings[i % 3], capacity, LECTURE));
        }
        for (int i = 0; i < labRooms; i++)
            rooms.add(new Room(rooms.size() + 1, "L" + (1 + i), buildings[i % 3], 60, LAB));
        int halls = (int) Math.ceil(hallMeetings / (config.slotCount() * roomUtilisation));
        for (int i = 0; i < halls; i++)
            rooms.add(new Room(rooms.size() + 1, "H" + (1 + i), buildings[i % 3], 130, LECTURE));
        return new Dataset(config, rooms, faculty, batches, courses);
    }

    private static void add(List<Course> courses, String code, String title, int facultyId, int batchId,
                            int meetings, RoomType type) {
        courses.add(new Course(courses.size() + 1, code, title, facultyId, batchId, meetings, type));
    }

    private static void addCombined(List<Course> courses, String code, String title, int facultyId,
                                    List<Integer> batchIds, int meetings) {
        courses.add(new Course(courses.size() + 1, code, title, facultyId, batchIds, meetings, LECTURE));
    }

    private static Set<TimeSlot> wholeDay(int day, SchedulingConfig config) {
        Set<TimeSlot> out = new HashSet<>();
        for (int p = 0; p < config.periodsPerDay(); p++) out.add(new TimeSlot(day, p));
        return out;
    }

    private static Set<TimeSlot> periods(SchedulingConfig config, int day, int... periods) {
        Set<TimeSlot> out = new HashSet<>();
        for (int p : periods) if (p < config.periodsPerDay()) out.add(new TimeSlot(day, p));
        return out;
    }
}
