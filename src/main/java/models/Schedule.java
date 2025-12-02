package models;

import java.util.Date;
import java.util.List;

public class Schedule {
    private String id;
    private Cargo cargo;
    private Locomotive locomotive;
    private List<Wagon> wagons;
    private Route route;
    private TimeSlot timeSlot;
    private Date createdAt;
    private String status = "PENDING";

    public Schedule() {
        this.createdAt = new Date();
    }

    public String getId() { return id; }
    public Cargo getCargo() { return cargo; }
    public Locomotive getLocomotive() { return locomotive; }
    public List<Wagon> getWagons() { return wagons; }
    public Route getRoute() { return route; }
    public TimeSlot getTimeSlot() { return timeSlot; }
    public Date getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }

    public void setId(String id) { this.id = id; }
    public void setCargo(Cargo cargo) { this.cargo = cargo; }
    public void setLocomotive(Locomotive locomotive) { this.locomotive = locomotive; }
    public void setWagons(List<Wagon> wagons) { this.wagons = wagons; }
    public void setRoute(Route route) { this.route = route; }
    public void setTimeSlot(TimeSlot timeSlot) { this.timeSlot = timeSlot; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public void setStatus(String status) { this.status = status; }
}