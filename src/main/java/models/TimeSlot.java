package models;

import java.util.Date;

public class TimeSlot {
    private Date startTime;
    private Date endTime;
    private boolean available = true;

    public Date getStartTime() { return startTime; }
    public Date getEndTime() { return endTime; }
    public boolean isAvailable() { return available; }

    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public void setAvailable(boolean available) { this.available = available; }
}