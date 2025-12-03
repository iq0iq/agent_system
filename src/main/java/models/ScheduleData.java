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

    public String getResourceId() { return resourceId; }
    public List<TimeSlot> getSchedule() { return schedule; }

    public void setSchedule(List<TimeSlot> schedule) {
        this.schedule = schedule;
    }

    public Date findNearestAvailableTimeAfter(Date afterTime, int durationMinutes) {
        if (afterTime == null) {
            throw new IllegalArgumentException("afterTime cannot be null");
        }

        if (schedule.isEmpty()) {
            return afterTime;
        }

        for (int i = 0; i <= schedule.size(); i++) {
            Date startTime;
            Date endTime;

            if (i == 0) {
                startTime = afterTime;
                TimeSlot firstSlot = schedule.get(0);
                endTime = firstSlot.getStartTime();
            } else if (i == schedule.size()) {
                TimeSlot lastSlot = schedule.get(schedule.size() - 1);
                startTime = lastSlot.getEndTime();
                endTime = new Date(startTime.getTime() + durationMinutes * 60000L);
            } else {
                TimeSlot prevSlot = schedule.get(i - 1);
                TimeSlot nextSlot = schedule.get(i);
                startTime = prevSlot.getEndTime();
                endTime = nextSlot.getStartTime();
            }

            if (startTime.before(afterTime)) {
                startTime = afterTime;
            }

            long availableDuration = (endTime.getTime() - startTime.getTime()) / 60000;
            if (availableDuration >= durationMinutes) {
                return startTime;
            }
        }

        TimeSlot lastSlot = schedule.get(schedule.size() - 1);
        Date suggestedTime = lastSlot.getEndTime();
        if (suggestedTime.before(afterTime)) {
            suggestedTime = afterTime;
        }
        return suggestedTime;
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
        return true;
    }
}