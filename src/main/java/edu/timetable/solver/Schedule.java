package edu.timetable.solver;

import java.util.Arrays;

/**
 * A (possibly partial) assignment of every meeting to a slot and a room. Room numbers are
 * indices into {@link Problem#rooms()}; -1 means unassigned.
 */
public final class Schedule {
    final int[] slot;
    final int[] room;

    public Schedule(int meetingCount) {
        slot = new int[meetingCount];
        room = new int[meetingCount];
        Arrays.fill(slot, -1);
        Arrays.fill(room, -1);
    }

    private Schedule(int[] slot, int[] room) {
        this.slot = slot;
        this.room = room;
    }

    public int slotOf(int meeting) {
        return slot[meeting];
    }

    public int roomOf(int meeting) {
        return room[meeting];
    }

    public void assign(int meeting, int slotIndex, int roomIndex) {
        slot[meeting] = slotIndex;
        room[meeting] = roomIndex;
    }

    public int size() {
        return slot.length;
    }

    public boolean isComplete() {
        for (int s : slot) if (s < 0) return false;
        return true;
    }

    public Schedule copy() {
        return new Schedule(slot.clone(), room.clone());
    }
}
