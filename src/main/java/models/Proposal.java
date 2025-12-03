package models;

import java.util.Date;

public class Proposal {
    private String agentId;
    private String resourceId;
    private Date availableTime;
    private boolean available;
    private String reason;

    public Proposal(String agentId, String resourceId, Date availableTime, boolean available) {
        this.agentId = agentId;
        this.resourceId = resourceId;
        this.availableTime = availableTime;
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
    public boolean isAvailable() { return available; }
    public String getReason() { return reason; }
}