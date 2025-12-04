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

        // Сортируем слоты по времени начала
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        // Проверяем первое окно (до первого слота)
        TimeSlot firstSlot = schedule.get(0);
        Date windowStart = afterTime;
        Date windowEnd = firstSlot.getStartTime();

        if (windowEnd.after(windowStart)) {
            long windowDuration = (windowEnd.getTime() - windowStart.getTime()) / 60000;
            if (windowDuration >= durationMinutes) {
                return windowStart;
            }
        }

        // Проверяем окна между слотами
        for (int i = 1; i < schedule.size(); i++) {
            TimeSlot prevSlot = schedule.get(i - 1);
            TimeSlot currSlot = schedule.get(i);

            windowStart = prevSlot.getEndTime();
            windowEnd = currSlot.getStartTime();

            // Если afterTime позже, чем начало окна, используем afterTime
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

        // Проверяем окно после последнего слота
        TimeSlot lastSlot = schedule.get(schedule.size() - 1);
        windowStart = lastSlot.getEndTime();

        // Если afterTime позже, используем afterTime
        if (windowStart.before(afterTime)) {
            windowStart = afterTime;
        }

        return windowStart;
    }

    /**
     * Находит оптимальное время для добавления составов,
     * учитывая возможность группировки
     */
    public Date findOptimizedTimeForComposition(Date preferredTime, int durationMinutes,
                                                int maxDelayMinutes) {
        if (schedule.isEmpty()) {
            return preferredTime;
        }

        // Сортируем слоты
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        // Ищем слот, к которому можно добавиться
        for (int i = 0; i < schedule.size(); i++) {
            TimeSlot slot = schedule.get(i);

            // Если слот доступен (можно расширить)
            if (!slot.isAvailable() && slot.getEndTime().after(preferredTime)) {
                // Проверяем возможность добавиться сразу после этого слота
                Date potentialStart = slot.getEndTime();
                long delay = (potentialStart.getTime() - preferredTime.getTime()) / 60000;

                // Если задержка приемлема
                if (delay <= maxDelayMinutes) {
                    // Проверяем, есть ли место после слота
                    Date potentialEnd = new Date(potentialStart.getTime() + durationMinutes * 60000L);

                    // Проверяем конфликты
                    boolean conflicts = false;
                    for (TimeSlot otherSlot : schedule) {
                        if (otherSlot != slot &&
                                potentialStart.before(otherSlot.getEndTime()) &&
                                potentialEnd.after(otherSlot.getStartTime())) {
                            conflicts = true;
                            break;
                        }
                    }

                    if (!conflicts) {
                        return potentialStart;
                    }
                }
            }
        }

        // Если не нашли возможности группировки, используем стандартный метод
        return findNearestAvailableTimeAfter(preferredTime, durationMinutes);
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

        // Сортируем после добавления
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        return true;
    }

    /**
     * Проверяет возможность добавления слота без конфликтов
     */
    public boolean canAddTimeSlot(Date startTime, Date endTime) {
        for (TimeSlot slot : schedule) {
            if (startTime.before(slot.getEndTime()) && endTime.after(slot.getStartTime())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Возвращает список свободных окон заданной длительности
     */
    public List<TimeSlot> findAvailableWindows(int durationMinutes, Date afterTime) {
        List<TimeSlot> windows = new ArrayList<>();

        if (schedule.isEmpty()) {
            TimeSlot window = new TimeSlot();
            window.setStartTime(afterTime);
            window.setEndTime(new Date(Long.MAX_VALUE)); // Бесконечное окно
            windows.add(window);
            return windows;
        }

        // Сортируем
        schedule.sort((s1, s2) -> s1.getStartTime().compareTo(s2.getStartTime()));

        // Первое окно
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

        // Окна между слотами
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

        // Окно после последнего слота
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