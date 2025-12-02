package models;

public class Wagon {
    private String id, type, currentStation;
    private double capacity;
    private boolean available = true;
    private String[] compatibleCargoTypes;

    public String getId() { return id; }
    public String getType() { return type; }
    public String getCurrentStation() { return currentStation; }
    public double getCapacity() { return capacity; }
    public boolean isAvailable() { return available; }
    public String[] getCompatibleCargoTypes() { return compatibleCargoTypes; }

    public void setId(String id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setCurrentStation(String currentStation) { this.currentStation = currentStation; }
    public void setCapacity(double capacity) { this.capacity = capacity; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setCompatibleCargoTypes(String[] compatibleCargoTypes) { this.compatibleCargoTypes = compatibleCargoTypes; }

    public boolean canCarryCargo(String cargoType, double weight) {
        if (!available || weight > capacity) return false;
        for (String type : compatibleCargoTypes) {
            if (type.equals(cargoType)) return true;
        }
        return false;
    }
}