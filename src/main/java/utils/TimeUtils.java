package utils;

import java.util.Date;
import java.util.List;
import models.Proposal;

/**
 * Утилиты для работы со временем и предложениями
 */
public class TimeUtils {

    /**
     * Выбирает лучшее предложение из списка
     * @param proposals список предложений
     * @return лучшее предложение или null если нет доступных
     */
    public static Proposal selectBestProposal(List<Proposal> proposals) {
        Proposal bestProposal = null;

        for (Proposal proposal : proposals) {
            if (proposal.isAvailable()) {
                if (bestProposal == null) {
                    bestProposal = proposal;
                } else {
                    // Выбираем самое раннее время, затем минимальную стоимость
                    if (proposal.getAvailableTime().before(bestProposal.getAvailableTime())) {
                        bestProposal = proposal;
                    } else if (proposal.getAvailableTime().equals(bestProposal.getAvailableTime())) {
                        if (proposal.getCost() < bestProposal.getCost()) {
                            bestProposal = proposal;
                        }
                    }
                }
            }
        }

        return bestProposal;
    }

    /**
     * Вычисляет длительность поездки на основе расстояния
     * @param distance расстояние в км
     * @return длительность в минутах
     */
    public static int calculateTripDuration(double distance) {
        // Предположим среднюю скорость 60 км/ч
        return (int) Math.ceil(distance / 60.0 * 60);
    }

    /**
     * Добавляет минуты к дате
     */
    public static Date addMinutes(Date date, int minutes) {
        return new Date(date.getTime() + minutes * 60000L);
    }
}