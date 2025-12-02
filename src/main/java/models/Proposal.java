package models;

import java.util.Date;

/**
 * Предложение от агента с временем выполнения
 */
public class Proposal {
    private String agentId;
    private String resourceId;
    private Date availableTime;
    private double cost; // Стоимость или приоритет
    private boolean available;
    private String reason;

    public Proposal(String agentId, String resourceId, Date availableTime, double cost, boolean available) {
        this.agentId = agentId;
        this.resourceId = resourceId;
        this.availableTime = availableTime;
        this.cost = cost;
        this.available = available;
    }

    public Proposal(String agentId, String reason) {
        this.agentId = agentId;
        this.available = false;
        this.reason = reason;
    }

    public String getAgentId() { return agentId; }
    public String getResourceId() { return resourceId; }
    public Date getAvailableTime() { return availableTime; }
    public double getCost() { return cost; }
    public boolean isAvailable() { return available; }
    public String getReason() { return reason; }

    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public void setAvailableTime(Date availableTime) { this.availableTime = availableTime; }
    public void setCost(double cost) { this.cost = cost; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        if (available) {
            return "Proposal{agentId='" + agentId + "', time=" + availableTime + ", cost=" + cost + "}";
        } else {
            return "Proposal{agentId='" + agentId + "', available=false, reason='" + reason + "'}";
        }
    }
}