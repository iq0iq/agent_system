package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import models.Route;
import utils.DataLoader;
import java.util.List;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class RoadAgent extends Agent {
    private String agentId;
    private List<Route> routes;
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // Храним отдельные расписания для каждого груза
    private Map<String, Map<String, Object>> schedules = new ConcurrentHashMap<>();
    private Map<String, String> cargoToScheduleMap = new ConcurrentHashMap<>();

    protected void setup() {
        agentId = (String) getArguments()[0];
        routes = DataLoader.getAllRoutes();

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("road");
        sd.setName("RoadService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        addBehaviour(new RoadRequestBehaviour());
        addBehaviour(new FinalConfirmationBehaviour());
        System.out.println(agentId + " started with " + routes.size() + " routes");
    }

    private class RoadRequestBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("TRANSPORT_REQUEST:")) {
                    handleTransportRequest(msg, content);
                }
            } else {
                block();
            }
        }

        private void handleTransportRequest(ACLMessage msg, String content) {
            System.out.println(agentId + ": Handling transport request: " + content);

            String[] parts = content.substring("TRANSPORT_REQUEST:".length()).split(":");
            String fromStation = parts[0];
            String toStation = parts[1];
            double totalWeight = Double.parseDouble(parts[2]);
            String wagonId = parts[3];
            String locomotiveId = parts[4];
            String cargoId = parts[5];

            Route route = findRoute(fromStation, toStation);

            if (route != null) {
                System.out.println(agentId + ": Route found from " + fromStation + " to " + toStation + " for cargo " + cargoId);

                // Создаем отдельное расписание для каждого груза
                String scheduleId = "SCHEDULE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

                Map<String, Object> scheduleData = new HashMap<>();
                scheduleData.put("scheduleId", scheduleId);
                scheduleData.put("fromStation", fromStation);
                scheduleData.put("toStation", toStation);
                scheduleData.put("distance", route.getDistance());
                scheduleData.put("wagonId", wagonId);
                scheduleData.put("locomotiveId", locomotiveId);
                scheduleData.put("totalWeight", totalWeight);
                scheduleData.put("cargoIds", new ArrayList<>(List.of(cargoId))); // Только один груз!
                scheduleData.put("status", "CREATED");
                scheduleData.put("creationTime", new Date());

                // Сохраняем расписание
                schedules.put(scheduleId, scheduleData);
                cargoToScheduleMap.put(cargoId, scheduleId);

                System.out.println(agentId + ": Created NEW schedule for cargo " + cargoId + ": " + scheduleId);
                System.out.println(agentId + ": Schedule details - Wagon: " + wagonId + ", Locomotive: " + locomotiveId);

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.CONFIRM);
                reply.setContent("SCHEDULE_CREATED:" + scheduleId);
                myAgent.send(reply);

                System.out.println(agentId + ": Schedule processed for cargo: " + cargoId + ", schedule ID: " + scheduleId);
            } else {
                System.out.println(agentId + ": Route NOT found from " + fromStation + " to " + toStation);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("ROUTE_NOT_FOUND");
                myAgent.send(reply);
            }
        }
    }

    private class FinalConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("BOOKING_CONFIRMED:")) {
                    String scheduleId = content.substring("BOOKING_CONFIRMED:".length());

                    Map<String, Object> scheduleData = schedules.get(scheduleId);
                    if (scheduleData != null && !"CONFIRMED".equals(scheduleData.get("status"))) {
                        scheduleData.put("status", "CONFIRMED");
                        scheduleData.put("confirmationTime", new Date());
                        scheduleData.put("confirmedBy", msg.getSender().getLocalName());

                        saveFinalSchedule(scheduleId, scheduleData);

                        System.out.println(agentId + ": Final schedule confirmed for schedule: " + scheduleId);

                        // Удаляем из маппинга после подтверждения
                        List<String> cargoIds = (List<String>) scheduleData.get("cargoIds");
                        for (String cargoId : cargoIds) {
                            cargoToScheduleMap.remove(cargoId);
                        }
                    } else if (scheduleData != null && "CONFIRMED".equals(scheduleData.get("status"))) {
                        System.out.println(agentId + ": Schedule already confirmed, ignoring duplicate confirmation");
                    }
                }
            } else {
                block();
            }
        }

        private void saveFinalSchedule(String scheduleId, Map<String, Object> scheduleData) {
            try {
                String filename = "schedules.json";

                try (FileWriter writer = new FileWriter(filename, true)) {
                    gson.toJson(scheduleData, writer);
                    writer.write(",\n");
                }

                System.out.println(agentId + ": FINAL schedule saved to: " + filename);

                // Выводим детали в консоль
                System.out.println("=== SCHEDULE DETAILS ===");
                System.out.println("Schedule ID: " + scheduleData.get("scheduleId"));
                System.out.println("From: " + scheduleData.get("fromStation") + " To: " + scheduleData.get("toStation"));
                System.out.println("Wagon ID: " + scheduleData.get("wagonId"));
                System.out.println("Locomotive ID: " + scheduleData.get("locomotiveId"));
                System.out.println("Cargo(s): " + scheduleData.get("cargoIds"));
                System.out.println("Total Weight: " + scheduleData.get("totalWeight"));
                System.out.println("Status: " + scheduleData.get("status"));
                System.out.println("========================");

            } catch (IOException e) {
                System.err.println(agentId + ": Error saving final schedule: " + e.getMessage());
            }
        }
    }

    private Route findRoute(String from, String to) {
        for (Route route : routes) {
            if (route.getFromStation().equals(from) && route.getToStation().equals(to)) {
                return route;
            }
        }
        return null;
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated");
    }
}