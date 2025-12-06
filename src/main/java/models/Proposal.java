package models;

import java.util.Date;

public class Proposal {
    private String agentId;
    private String resourceId;
    private Date availableTime;
    private boolean available;
    private String reason;
    private String additionalData; // Новое поле

    // Конструкторы и методы
    public void setAdditionalData(String data) {
        this.additionalData = data;
    }

    public String getAdditionalData() {
        return additionalData;
    }

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