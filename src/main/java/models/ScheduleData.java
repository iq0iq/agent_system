package models;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ScheduleData {
    private String resourceId;
    private List<TimeSlot> schedule;

    public ScheduleData(String resourceId) {
        this.resourceId = resourceId;
        this.schedule = new ArrayList<>();
    }

    public List<TimeSlot> getSchedule() { return schedule; }

    public Date findNearestAvailableTimeAfter(Date afterTime, int durationMinutes) {
        if (afterTime == null) {
            throw new IllegalArgumentException("afterTime cannot be null");
        }

        if (schedule.isEmpty()) {
            return afterTime;
        }
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        TimeSlot firstSlot = schedule.get(0);
        Date windowStart = afterTime;
        Date windowEnd = firstSlot.getStartTime();

        if (windowEnd.after(windowStart)) {
            long windowDuration = (windowEnd.getTime() - windowStart.getTime()) / 60000;
            if (windowDuration >= durationMinutes) {
                return windowStart;
            }
        }
        for (int i = 1; i < schedule.size(); i++) {
            TimeSlot prevSlot = schedule.get(i - 1);
            TimeSlot currSlot = schedule.get(i);

            windowStart = prevSlot.getEndTime();
            windowEnd = currSlot.getStartTime();

            if (windowStart.before(afterTime)) {
                windowStart = afterTime;
            }

            if (windowEnd.after(windowStart)) {
                long windowDuration = (windowEnd.getTime() - windowStart.getTime()) / 60000;
                if (windowDuration >= durationMinutes) {
                    return windowStart;
                }
            }
        }

        TimeSlot lastSlot = schedule.get(schedule.size() - 1);
        windowStart = lastSlot.getEndTime();

        if (windowStart.before(afterTime)) {
            windowStart = afterTime;
        }

        return windowStart;
    }

    public boolean reserveTimeSlot(Date startTime, Date endTime) {
        TimeSlot newSlot = new TimeSlot();
        newSlot.setStartTime(startTime);
        newSlot.setEndTime(endTime);
        newSlot.setAvailable(false);

        int insertIndex = schedule.size();
        for (int i = 0; i < schedule.size(); i++) {
            if (schedule.get(i).getStartTime().after(startTime)) {
                insertIndex = i;
                break;
            }
        }
        schedule.add(insertIndex, newSlot);
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));
        return true;
    }

    public boolean canAddTimeSlot(Date startTime, Date endTime) {
        for (TimeSlot slot : schedule) {
            if (startTime.before(slot.getEndTime()) && endTime.after(slot.getStartTime())) {
                return false;
            }
        }
        return true;
    }

    public List<TimeSlot> findAvailableWindows(int durationMinutes, Date afterTime) {
        List<TimeSlot> windows = new ArrayList<>();

        if (schedule.isEmpty()) {
            TimeSlot window = new TimeSlot();
            window.setStartTime(afterTime);
            window.setEndTime(new Date(Long.MAX_VALUE)); // Бесконечное окно
            windows.add(window);
            return windows;
        }

        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        Date windowStart = afterTime;
        Date windowEnd = schedule.get(0).getStartTime();

        if (windowEnd.after(windowStart)) {
            long duration = (windowEnd.getTime() - windowStart.getTime()) / 60000;
            if (duration >= durationMinutes) {
                TimeSlot window = new TimeSlot();
                window.setStartTime(windowStart);
                window.setEndTime(windowEnd);
                windows.add(window);
            }
        }
        for (int i = 1; i < schedule.size(); i++) {
            TimeSlot prev = schedule.get(i - 1);
            TimeSlot curr = schedule.get(i);

            windowStart = prev.getEndTime();
            windowEnd = curr.getStartTime();

            if (windowEnd.after(windowStart)) {
                long duration = (windowEnd.getTime() - windowStart.getTime()) / 60000;
                if (duration >= durationMinutes) {
                    TimeSlot window = new TimeSlot();
                    window.setStartTime(windowStart);
                    window.setEndTime(windowEnd);
                    windows.add(window);
                }
            }
        }

        TimeSlot last = schedule.get(schedule.size() - 1);
        windowStart = last.getEndTime();
        windowEnd = new Date(windowStart.getTime() + durationMinutes * 60000L * 10); // Большое окно

        TimeSlot window = new TimeSlot();
        window.setStartTime(windowStart);
        window.setEndTime(windowEnd);
        windows.add(window);

        return windows;
    }
}