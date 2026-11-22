package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a schedule against every hard constraint directly from the dataset, without using the
 * conflict graph, the student-group table or the domains. It is deliberately independent of the
 * solvers so it can serve as an audit of their output.
 */
public final class HardConstraintValidator {
    private HardConstraintValidator() {
    }

    public static List<String> violations(Problem p, Schedule s) {
        List<String> out = new ArrayList<>();
        SchedulingConfig config = p.config();
        int periods = config.periodsPerDay();
        Map<Integer, Batch> batchById = new HashMap<>();
        for (Batch b : p.dataset().batches()) batchById.put(b.id(), b);

        Map<String, Integer> seen = new HashMap<>();
        Map<String, Integer> teachingPerDay = new HashMap<>();
        for (int i = 0; i < p.meetingCount(); i++) {
            Meeting m = p.meeting(i);
            int start = s.slotOf(i);
            if (start < 0) {
                out.add("Unassigned: " + m.label());
                continue;
            }
            int length = m.course().periodsPerMeeting();
            Room room = p.room(s.roomOf(i));
            String when = config.slotLabel(start);
            if (start % periods + length > periods)
                out.add(m.label() + " runs past the end of the day from " + when);
            int lunch = config.lunchAfter(), first = start % periods;
            if (lunch > 0 && first < lunch && first + length > lunch)
                out.add(m.label() + " runs through the lunch break from " + when);
            if (room.type() != m.course().roomType())
                out.add(m.label() + " needs a " + m.course().roomType() + " room but is in " + room.code());
            if (room.capacity() < m.studentCount())
                out.add(m.label() + " has " + m.studentCount() + " students but " + room.code() + " seats " + room.capacity());

            // The batches whose students attend. Lab groups are checked against their own
            // classes and against the whole-class meetings of their parent batch.
            Set<Integer> present = new HashSet<>();
            for (Batch b : m.batches()) present.add(b.id());
            teachingPerDay.merge(m.faculty().id() + "@" + start / periods, Math.min(length, periods - start % periods), Integer::sum);
            for (int t = start; t < start + length && t / periods == start / periods; t++) {
                String at = config.slotLabel(t);
                if (m.faculty().unavailable().contains(TimeSlot.fromIndex(t, periods)))
                    out.add(m.faculty().name() + " is unavailable at " + at + " but teaches " + m.label());
                for (int id : present) {
                    Batch b = batchById.get(id);
                    if (b.isGroup()) {
                        // A lab group clashes with its own other classes and with any class of its parent batch.
                        clash(seen, "group " + b.id() + "@" + t, i, p, out, "Lab group " + b.name() + " double-booked at " + at);
                        clashWithWhole(seen, b.parentId(), t, i, p, out, batchById, at);
                    } else {
                        clash(seen, "whole " + b.id() + "@" + t, i, p, out, "Batch " + b.name() + " double-booked at " + at);
                        // A whole-class meeting also blocks every lab group of the batch.
                        seen.putIfAbsent("busy " + b.id() + "@" + t, i);
                        Integer lab = seen.get("anygroup " + b.id() + "@" + t);
                        if (lab != null && lab != i)
                            out.add("Batch " + b.name() + " double-booked at " + at + ": " + p.meeting(lab).label() + " vs " + m.label());
                    }
                }
                clash(seen, "faculty " + m.faculty().id() + "@" + t, i, p, out, m.faculty().name() + " double-booked at " + at);
                clash(seen, "room " + room.id() + "@" + t, i, p, out, "Room " + room.code() + " double-booked at " + at);
            }
        }
        for (Faculty f : p.dataset().faculty()) {
            if (!f.hasDailyLimit()) continue;
            for (int d = 0; d < config.days(); d++) {
                int load = teachingPerDay.getOrDefault(f.id() + "@" + d, 0);
                if (load > f.maxPerDay())
                    out.add(f.name() + " teaches " + load + " periods on " + SchedulingConfig.dayName(d) + ", above the limit of " + f.maxPerDay());
            }
        }
        return out;
    }

    /** Records that a lab group of {@code parentId} is busy at t, and reports a whole-class meeting at the same time. */
    private static void clashWithWhole(Map<String, Integer> seen, int parentId, int t, int meeting, Problem p, List<String> out,
                                       Map<Integer, Batch> batchById, String at) {
        seen.putIfAbsent("anygroup " + parentId + "@" + t, meeting);
        Integer whole = seen.get("busy " + parentId + "@" + t);
        if (whole != null && whole != meeting)
            out.add("Batch " + batchById.get(parentId).name() + " double-booked at " + at + ": "
                    + p.meeting(whole).label() + " vs " + p.meeting(meeting).label());
    }

    private static void clash(Map<String, Integer> seen, String key, int meeting, Problem p, List<String> out, String msg) {
        Integer other = seen.putIfAbsent(key, meeting);
        if (other != null && other != meeting) out.add(msg + ": " + p.meeting(other).label() + " vs " + p.meeting(meeting).label());
    }

    public static boolean isFeasible(Problem p, Schedule s) {
        return violations(p, s).isEmpty();
    }
}
