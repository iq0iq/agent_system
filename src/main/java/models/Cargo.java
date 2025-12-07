package models;

public class Cargo {
    private String id, type, fromStation, toStation;
    private double weight;

    public String getId() { return id; }
    public String getType() { return type; }
    public String getFromStation() { return fromStation; }
    public String getToStation() { return toStation; }
    public double getWeight() { return weight; }
}