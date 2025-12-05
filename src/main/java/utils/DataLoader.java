package utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class DataLoader {
    private static final Gson gson = new Gson();
    private static JsonObject data;
    private static Date simulationStartTime;

    static {
        try {
            InputStream inputStream = DataLoader.class.getClassLoader()
                    .getResourceAsStream("input_data.json");
            if (inputStream == null) {
                throw new RuntimeException("File input_data.json not found in resources!");
            }
            Reader reader = new InputStreamReader(inputStream);
            data = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            // Загружаем время начала симуляции
            String timeStr = data.get("simulationStartTime").getAsString();
            simulationStartTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timeStr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load data: " + e.getMessage());
        }
    }

    public static Date getSimulationStartTime() {
        return simulationStartTime;
    }

    public static Cargo getCargoForAgent(String agentId) {
        var cargoAgents = data.getAsJsonObject("agents").getAsJsonArray("cargo");
        for (var agent : cargoAgents) {
            JsonObject agentObj = agent.getAsJsonObject();
            if (agentObj.get("agentId").getAsString().equals(agentId)) {
                return gson.fromJson(agentObj.get("cargo"), Cargo.class);
            }
        }
        return null;
    }

    public static Wagon getWagonForAgent(String agentId) {
        var wagonAgents = data.getAsJsonObject("agents").getAsJsonArray("wagon");
        for (var agent : wagonAgents) {
            JsonObject agentObj = agent.getAsJsonObject();
            if (agentObj.get("agentId").getAsString().equals(agentId)) {
                return gson.fromJson(agentObj.get("wagon"), Wagon.class);
            }
        }
        return null;
    }

    public static Locomotive getLocomotiveForAgent(String agentId) {
        var locoAgents = data.getAsJsonObject("agents").getAsJsonArray("locomotive");
        for (var agent : locoAgents) {
            JsonObject agentObj = agent.getAsJsonObject();
            if (agentObj.get("agentId").getAsString().equals(agentId)) {
                Locomotive locomotive = gson.fromJson(agentObj.get("locomotive"), Locomotive.class);

                // Устанавливаем скорость по умолчанию, если она не указана
                if (locomotive.getSpeed() == 0) {
                    locomotive.setSpeed(60.0); // значение по умолчанию 60 км/ч
                }

                return locomotive;
            }
        }
        return null;
    }

    public static Route getRouteForRoadAgent(String agentId) {
        var roadAgents = data.getAsJsonObject("agents").getAsJsonArray("road");
        for (var agent : roadAgents) {
            JsonObject agentObj = agent.getAsJsonObject();
            if (agentObj.get("agentId").getAsString().equals(agentId)) {
                return gson.fromJson(agentObj.get("route"), Route.class);
            }
        }
        return null;
    }

    public static List<Route> getAllRoutes() {
        List<Route> routes = new ArrayList<>();
        var routesArray = data.getAsJsonArray("routes");
        for (var route : routesArray) {
            routes.add(gson.fromJson(route, Route.class));
        }
        return routes;
    }

    public static List<String> getAllCargoAgentIds() {
        return getAgentIds("cargo");
    }

    public static List<String> getAllWagonAgentIds() {
        return getAgentIds("wagon");
    }

    public static List<String> getAllLocomotiveAgentIds() {
        return getAgentIds("locomotive");
    }

    public static List<String> getAllRoadAgentIds() {
        return getAgentIds("road");
    }

    private static List<String> getAgentIds(String type) {
        List<String> ids = new ArrayList<>();
        var agents = data.getAsJsonObject("agents").getAsJsonArray(type);
        for (var agent : agents) {
            ids.add(agent.getAsJsonObject().get("agentId").getAsString());
        }
        return ids;
    }

    public static Station getStationById(String stationId) {
        var stationsArray = data.getAsJsonArray("stations");
        for (var station : stationsArray) {
            JsonObject stationObj = station.getAsJsonObject();
            if (stationObj.get("id").getAsString().equals(stationId)) {
                return gson.fromJson(stationObj, Station.class);
            }
        }
        return null;
    }
}