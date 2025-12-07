package utils;

import java.util.Date;
import java.util.List;
import models.Proposal;

public class TimeUtils {
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

    public static int calculateTripDuration(double distance, double locomotiveSpeed) {
        return (int) Math.ceil((distance / locomotiveSpeed) * 60);
    }

    public static Date addMinutes(Date date, int minutes) {
        if (date == null) return null;
        return new Date(date.getTime() + minutes * 60000L);
    }
}