package models;

public class Locomotive {
    private String id, type, currentStation;
    private double maxWeightCapacity;
    private boolean available = true;

    public String getId() { return id; }
    public String getType() { return type; }
    public String getCurrentStation() { return currentStation; }
    public double getMaxWeightCapacity() { return maxWeightCapacity; }
    public boolean isAvailable() { return available; }

    public void setId(String id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setCurrentStation(String currentStation) { this.currentStation = currentStation; }
    public void setMaxWeightCapacity(double maxWeightCapacity) { this.maxWeightCapacity = maxWeightCapacity; }
    public void setAvailable(boolean available) { this.available = available; }

    public boolean canPullWeight(double weight) {
        return available && weight <= maxWeightCapacity;
    }
}