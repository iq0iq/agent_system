package models;

public class Cargo {
    private String id, type, fromStation, toStation, priority, status = "PENDING";
    private double weight;

    public String getId() { return id; }
    public String getType() { return type; }
    public String getFromStation() { return fromStation; }
    public String getToStation() { return toStation; }
    public String getPriority() { return priority; }
    public String getStatus() { return status; }
    public double getWeight() { return weight; }

    public void setId(String id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setFromStation(String fromStation) { this.fromStation = fromStation; }
    public void setToStation(String toStation) { this.toStation = toStation; }
    public void setPriority(String priority) { this.priority = priority; }
    public void setStatus(String status) { this.status = status; }
    public void setWeight(double weight) { this.weight = weight; }
}