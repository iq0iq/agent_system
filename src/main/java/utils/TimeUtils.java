package utils;

import java.util.Date;
import java.util.List;
import models.Proposal;

public class TimeUtils {

    /**
     * Выбирает лучшее предложение (самое раннее время)
     */
    public static Proposal selectBestProposal(List<Proposal> proposals) {
        Proposal bestProposal = null;
        Date bestTime = null;

        for (Proposal proposal : proposals) {
            if (proposal.isAvailable() && proposal.getAvailableTime() != null) {
                if (bestProposal == null) {
                    bestProposal = proposal;
                    bestTime = proposal.getAvailableTime();
                } else if (proposal.getAvailableTime().before(bestTime)) {
                    bestProposal = proposal;
                    bestTime = proposal.getAvailableTime();
                }
            }
        }

        return bestProposal;
    }

    /**
     * Выбирает лучшее предложение с учетом весовых характеристик
     */
    public static Proposal selectBestProposalForComposition(List<Proposal> proposals,
                                                            double requiredWeight) {
        Proposal bestProposal = null;
        Date bestTime = null;

        for (Proposal proposal : proposals) {
            if (proposal.isAvailable() && proposal.getAvailableTime() != null) {
                // Здесь можно добавить дополнительные критерии отбора
                // например, проверка на возможность перевозки веса
                if (bestProposal == null) {
                    bestProposal = proposal;
                    bestTime = proposal.getAvailableTime();
                } else if (proposal.getAvailableTime().before(bestTime)) {
                    bestProposal = proposal;
                    bestTime = proposal.getAvailableTime();
                }
            }
        }

        return bestProposal;
    }

    /**
     * Рассчитывает продолжительность поездки в минутах
     * с учетом скорости локомотива
     */
    public static int calculateTripDuration(double distance, double locomotiveSpeed) {
        // distance в километрах
        // время в минутах = (расстояние / скорость) * 60
        if (locomotiveSpeed <= 0) {
            locomotiveSpeed = 60.0; // значение по умолчанию, если скорость не указана
        }
        return (int) Math.ceil((distance / locomotiveSpeed) * 60);
    }

    /**
     * Рассчитывает продолжительность поездки в минутах
     * с использованием средней скорости по умолчанию (60 км/ч)
     */
    public static int calculateTripDuration(double distance) {
        return calculateTripDuration(distance, 60.0);
    }

    /**
     * Добавляет минуты к дате
     */
    public static Date addMinutes(Date date, int minutes) {
        if (date == null) return null;
        return new Date(date.getTime() + minutes * 60000L);
    }

    /**
     * Вычитает минуты из даты
     */
    public static Date subtractMinutes(Date date, int minutes) {
        if (date == null) return null;
        return new Date(date.getTime() - minutes * 60000L);
    }

    /**
     * Проверяет, можно ли добавить вагон к существующему расписанию
     * с учетом допустимой задержки
     */
    public static boolean canAddToSchedule(Date currentDeparture,
                                           Date newAvailableTime,
                                           int maxDelayMinutes) {
        if (currentDeparture == null || newAvailableTime == null) {
            return false;
        }

        // Если новый вагон готов раньше или одновременно, можно добавить
        if (!newAvailableTime.after(currentDeparture)) {
            return true;
        }

        // Проверяем задержку
        long delayMinutes = (newAvailableTime.getTime() - currentDeparture.getTime()) / 60000;
        return delayMinutes <= maxDelayMinutes;
    }

    /**
     * Находит оптимальное время отправления для группы вагонов
     */
    public static Date findOptimalDepartureTime(List<Date> availableTimes,
                                                int maxDelayMinutes) {
        if (availableTimes == null || availableTimes.isEmpty()) {
            return null;
        }

        // Сортируем времена
        availableTimes.sort(Date::compareTo);

        // Берем самое позднее время
        Date latestTime = availableTimes.get(availableTimes.size() - 1);

        // Проверяем, все ли вагоны могут быть готовы к этому времени
        // с учетом максимальной задержки
        for (Date time : availableTimes) {
            long delay = (latestTime.getTime() - time.getTime()) / 60000;
            if (delay > maxDelayMinutes) {
                // Некоторые вагоны не могут ждать так долго
                // Выбираем время, которое удовлетворяет большинству
                return findCompromiseTime(availableTimes, maxDelayMinutes);
            }
        }

        return latestTime;
    }

    /**
     * Находит компромиссное время для группы вагонов
     */
    private static Date findCompromiseTime(List<Date> availableTimes, int maxDelayMinutes) {
        if (availableTimes == null || availableTimes.isEmpty()) {
            return null;
        }

        // Сортируем времена
        availableTimes.sort(Date::compareTo);

        Date bestTime = null;
        int maxSatisfied = 0;

        // Перебираем возможные времена отправления
        for (int i = 0; i < availableTimes.size(); i++) {
            Date candidate = availableTimes.get(i);
            int satisfied = 0;

            // Считаем, сколько вагонов могут ждать до этого времени
            for (Date time : availableTimes) {
                long delay = (candidate.getTime() - time.getTime()) / 60000;
                if (delay <= maxDelayMinutes && delay >= 0) {
                    satisfied++;
                }
            }

            if (satisfied > maxSatisfied) {
                maxSatisfied = satisfied;
                bestTime = candidate;
            }
        }

        return bestTime;
    }

    /**
     * Рассчитывает общую задержку для группы вагонов
     */
    public static long calculateTotalDelay(List<Date> availableTimes, Date departureTime) {
        if (availableTimes == null || departureTime == null) {
            return 0;
        }

        long totalDelay = 0;

        for (Date time : availableTimes) {
            if (time.before(departureTime)) {
                long delay = (departureTime.getTime() - time.getTime()) / 60000;
                totalDelay += delay;
            }
        }

        return totalDelay;
    }
}