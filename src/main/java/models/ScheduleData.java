package models;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Данные расписания для ресурса (вагон, локомотив, дорога)
 */
public class ScheduleData {
    private String resourceId;
    private List<TimeSlot> schedule;
    private Date lastUpdated;

    public ScheduleData(String resourceId) {
        this.resourceId = resourceId;
        this.schedule = new ArrayList<>();
        this.lastUpdated = new Date();
    }

    public String getResourceId() { return resourceId; }
    public List<TimeSlot> getSchedule() { return schedule; }
    public Date getLastUpdated() { return lastUpdated; }

    public void setSchedule(List<TimeSlot> schedule) {
        this.schedule = schedule;
        this.lastUpdated = new Date();
    }

    /**
     * Находит ближайшее доступное время для заданной продолжительности
     * @param durationMinutes продолжительность в минутах
     * @return ближайшее доступное время или null если нет свободного времени
     */
    public Date findNearestAvailableTime(int durationMinutes) {
        Date now = new Date();

        // Если расписание пустое, можем начать сейчас
        if (schedule.isEmpty()) {
            return now;
        }

        // Ищем первый свободный интервал достаточной длины
        for (int i = 0; i <= schedule.size(); i++) {
            Date startTime;
            Date endTime;

            if (i == 0) {
                // Проверяем интервал от сейчас до первого занятия
                startTime = now;
                TimeSlot firstSlot = schedule.get(0);
                endTime = firstSlot.getStartTime();
            } else if (i == schedule.size()) {
                // Проверяем интервал после последнего занятия
                TimeSlot lastSlot = schedule.get(schedule.size() - 1);
                startTime = lastSlot.getEndTime();
                endTime = new Date(startTime.getTime() + durationMinutes * 60000L);
            } else {
                // Проверяем интервал между двумя занятиями
                TimeSlot prevSlot = schedule.get(i - 1);
                TimeSlot nextSlot = schedule.get(i);
                startTime = prevSlot.getEndTime();
                endTime = nextSlot.getStartTime();
            }

            // Проверяем, достаточно ли длинный интервал
            long availableDuration = (endTime.getTime() - startTime.getTime()) / 60000;
            if (availableDuration >= durationMinutes) {
                return startTime;
            }
        }

        // Если не нашли подходящего интервала, предлагаем время после последнего занятия
        TimeSlot lastSlot = schedule.get(schedule.size() - 1);
        return lastSlot.getEndTime();
    }

    /**
     * Резервирует временной слот
     * @param startTime время начала
     * @param endTime время окончания
     * @return true если резервирование успешно
     */
    public boolean reserveTimeSlot(Date startTime, Date endTime) {
        TimeSlot newSlot = new TimeSlot();
        newSlot.setStartTime(startTime);
        newSlot.setEndTime(endTime);
        newSlot.setAvailable(false);

        // Находим правильную позицию для вставки (сохраняем порядок)
        int insertIndex = 0;
        for (int i = 0; i < schedule.size(); i++) {
            if (schedule.get(i).getStartTime().after(startTime)) {
                insertIndex = i;
                break;
            }
        }

        schedule.add(insertIndex, newSlot);
        lastUpdated = new Date();
        return true;
    }
}