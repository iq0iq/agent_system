package models;

public class Route {
    private String fromStation, toStation;
    private double distance;

    public String getFromStation() { return fromStation; }
    public String getToStation() { return toStation; }
    public double getDistance() { return distance; }

    public void setFromStation(String fromStation) { this.fromStation = fromStation; }
    public void setToStation(String toStation) { this.toStation = toStation; }
    public void setDistance(double distance) { this.distance = distance; }
}