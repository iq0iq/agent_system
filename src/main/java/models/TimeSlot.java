package models;

import java.util.Date;

public class TimeSlot {
    private String id;
    private Date startTime;
    private Date endTime;
    private boolean available = true;
    private String routeId;

    public TimeSlot() {}

    public TimeSlot(String id, Date start, Date end, String routeId) {
        this.id = id;
        this.startTime = start;
        this.endTime = end;
        this.routeId = routeId;
    }

    public String getId() { return id; }
    public Date getStartTime() { return startTime; }
    public Date getEndTime() { return endTime; }
    public boolean isAvailable() { return available; }
    public String getRouteId() { return routeId; }

    public void setId(String id) { this.id = id; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setRouteId(String routeId) { this.routeId = routeId; }
}