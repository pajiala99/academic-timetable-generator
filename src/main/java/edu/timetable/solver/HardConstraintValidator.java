package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Room;
import edu.timetable.model.TimeSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a schedule against every hard constraint directly from the dataset, without using the
 * conflict graph or domains. It is deliberately independent of the solvers so it can serve as
 * an audit of their output.
 */
public final class HardConstraintValidator {
    private HardConstraintValidator() {
    }

    public static List<String> violations(Problem p, Schedule s) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < p.meetingCount(); i++) {
            Meeting m = p.meeting(i);
            int slot = s.slotOf(i);
            if (slot < 0) {
                out.add("Unassigned: " + m.label());
                continue;
            }
            Room room = p.room(s.roomOf(i));
            String when = p.config().slotLabel(slot);
            if (room.type() != m.course().roomType())
                out.add(m.label() + " needs a " + m.course().roomType() + " room but is in " + room.code());
            if (room.capacity() < m.studentCount())
                out.add(m.label() + " has " + m.studentCount() + " students but " + room.code() + " seats " + room.capacity());
            if (m.faculty().unavailable().contains(TimeSlot.fromIndex(slot, p.config().periodsPerDay())))
                out.add(m.faculty().name() + " is unavailable at " + when + " but teaches " + m.label());
            for (Batch batch : m.batches())
                clash(seen, "batch " + batch.id() + "@" + slot, i, p, out, "Batch " + batch.name() + " double-booked at " + when);
            clash(seen, "faculty " + m.faculty().id() + "@" + slot, i, p, out, m.faculty().name() + " double-booked at " + when);
            clash(seen, "room " + room.id() + "@" + slot, i, p, out, "Room " + room.code() + " double-booked at " + when);
        }
        return out;
    }

    private static void clash(Map<String, Integer> seen, String key, int meeting, Problem p, List<String> out, String msg) {
        Integer other = seen.putIfAbsent(key, meeting);
        if (other != null) out.add(msg + ": " + p.meeting(other).label() + " vs " + p.meeting(meeting).label());
    }

    public static boolean isFeasible(Problem p, Schedule s) {
        return violations(p, s).isEmpty();
    }
}
