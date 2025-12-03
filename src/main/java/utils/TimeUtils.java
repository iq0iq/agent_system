package utils;

import java.util.Date;
import java.util.List;
import models.Proposal;

public class TimeUtils {
    public static Proposal selectBestProposal(List<Proposal> proposals) {
        Proposal bestProposal = null;

        for (Proposal proposal : proposals) {
            if (proposal.isAvailable()) {
                if (bestProposal == null) {
                    bestProposal = proposal;
                } else if (proposal.getAvailableTime().before(bestProposal.getAvailableTime())) {
                    bestProposal = proposal;
                }
            }
        }

        return bestProposal;
    }

    public static int calculateTripDuration(double distance) {
        return (int) Math.ceil(distance / 60.0 * 60);
    }

    public static Date addMinutes(Date date, int minutes) {
        return new Date(date.getTime() + minutes * 60000L);
    }
}