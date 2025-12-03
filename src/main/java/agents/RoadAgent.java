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
import models.ScheduleData;
import models.Proposal;
import models.TimeSlot;
import utils.DataLoader;
import utils.TimeUtils;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.IOException;

public class RoadAgent extends Agent {
    private String agentId;
    private Route route;
    private ScheduleData scheduleData;
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private class LocomotiveRequest {
        String cargoId;
        String fromStation;
        String toStation;
        double totalWeight;
        String locomotiveId;
        String wagonIds;
        Date trainAvailableTime; // Свободное время поезда (локомотива)
        ACLMessage originalMessage;

        LocomotiveRequest(String cargoId, String fromStation, String toStation,
                          double totalWeight, String locomotiveId, String wagonIds,
                          Date trainAvailableTime, ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.totalWeight = totalWeight;
            this.locomotiveId = locomotiveId;
            this.wagonIds = wagonIds;
            this.trainAvailableTime = trainAvailableTime;
            this.originalMessage = originalMessage;
        }
    }

    private LocomotiveRequest currentRequest = null;

    protected void setup() {
        agentId = (String) getArguments()[0];
        route = DataLoader.getRouteForRoadAgent(agentId);

        if (route == null) {
            System.out.println(agentId + ": No route found for this road agent!");
            doDelete();
            return;
        }

        scheduleData = new ScheduleData("ROUTE_" + route.getFromStation() + "_" + route.getToStation());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("road");
        sd.setName("RoadService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        addBehaviour(new LocomotiveRequestBehaviour());
        addBehaviour(new AcceptProposalBehaviour());
        System.out.println(agentId + " started for route: " + route.getFromStation() +
                " -> " + route.getToStation() + ", schedule size: " + scheduleData.getSchedule().size());
    }

    private class LocomotiveRequestBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest == null) {
                String content = msg.getContent();
                if (content.startsWith("LOCOMOTIVE_REQUEST:")) {
                    handleLocomotiveRequest(msg, content);
                }
            } else {
                block();
            }
        }

        private void handleLocomotiveRequest(ACLMessage msg, String content) {
            String[] parts = content.substring("LOCOMOTIVE_REQUEST:".length()).split(":");
            String cargoId = parts[0];
            String fromStation = parts[1];
            String toStation = parts[2];
            double totalWeight = Double.parseDouble(parts[3]);
            String locomotiveId = parts[4];
            String wagonIds = parts[5];
            Date trainAvailableTime = new Date(Long.parseLong(parts[6]));

            currentRequest = new LocomotiveRequest(cargoId, fromStation, toStation, totalWeight,
                    locomotiveId, wagonIds, trainAvailableTime, msg);

            if (!route.getFromStation().equals(fromStation) || !route.getToStation().equals(toStation)) {
                System.out.println(agentId + ": Route mismatch");
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("ROUTE_MISMATCH");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());

            // Используем свободное время поезда для поиска ближайшего доступного времени
            Date availableTime = scheduleData.findNearestAvailableTimeAfter(trainAvailableTime, tripDuration);

            ACLMessage reply = currentRequest.originalMessage.createReply();
            reply.setPerformative(ACLMessage.PROPOSE);
            reply.setContent(availableTime.getTime() + ":" +
                    "ROUTE_" + fromStation + "_" + toStation);
            myAgent.send(reply);

            System.out.println(agentId + ": Sent proposal to " + msg.getSender().getLocalName() +
                    " - requested time: " + trainAvailableTime +
                    ", available time: " + availableTime +
                    ", duration: " + tripDuration + " min");
        }
    }

    private class AcceptProposalBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest != null) {
                String content = msg.getContent();
                if (content.startsWith("ACCEPT_PROPOSAL:")) {
                    String[] parts = content.substring("ACCEPT_PROPOSAL:".length()).split(":");
                    String cargoId = parts[0];
                    String toStation = parts[1];

                    if (currentRequest.cargoId.equals(cargoId)) {
                        int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());

                        // Находим ближайшее доступное время после времени готовности поезда
                        Date departureTime = scheduleData.findNearestAvailableTimeAfter(
                                currentRequest.trainAvailableTime, tripDuration);
                        Date arrivalTime = TimeUtils.addMinutes(departureTime, tripDuration);

                        scheduleData.reserveTimeSlot(departureTime, arrivalTime);

                        String scheduleId = "SCHEDULE_" + System.currentTimeMillis() + "_" +
                                currentRequest.locomotiveId;
                        createSchedule(scheduleId, departureTime, arrivalTime, tripDuration);

                        System.out.println(agentId + ": Schedule created: " + scheduleId +
                                " for locomotive: " + currentRequest.locomotiveId +
                                ", requested time: " + currentRequest.trainAvailableTime +
                                ", departure: " + departureTime +
                                ", arrival: " + arrivalTime);

                        ACLMessage confirmMsg = new ACLMessage(ACLMessage.INFORM);
                        confirmMsg.addReceiver(msg.getSender());
                        confirmMsg.setContent("SCHEDULE_CREATED:" + scheduleId + ":" +
                                departureTime.getTime() + ":" + arrivalTime.getTime());
                        myAgent.send(confirmMsg);

                        currentRequest = null;
                    }
                }
            } else {
                block();
            }
        }

        private void createSchedule(String scheduleId, Date departureTime, Date arrivalTime, int tripDuration) {
            Map<String, Object> scheduleData = new HashMap<>();
            scheduleData.put("scheduleId", scheduleId);
            scheduleData.put("fromStation", route.getFromStation());
            scheduleData.put("toStation", route.getToStation());
            scheduleData.put("distance", route.getDistance());
            scheduleData.put("cargoId", currentRequest.cargoId);
            scheduleData.put("wagonIds", currentRequest.wagonIds);
            scheduleData.put("locomotiveId", currentRequest.locomotiveId);
            scheduleData.put("totalWeight", currentRequest.totalWeight);
            scheduleData.put("trainAvailableTime", currentRequest.trainAvailableTime);
            scheduleData.put("departureTime", departureTime);
            scheduleData.put("arrivalTime", arrivalTime);
            scheduleData.put("duration", tripDuration + " min");
            scheduleData.put("creationTime", new Date());

            saveScheduleToFile(scheduleData);
        }

        private void saveScheduleToFile(Map<String, Object> scheduleData) {
            try {
                String filename = "schedules.json";
                boolean fileExists = new java.io.File(filename).exists();

                try (FileWriter writer = new FileWriter(filename, true)) {
                    if (!fileExists) {
                        writer.write("[\n");
                    } else {
                        writer.write(",\n");
                    }
                    gson.toJson(scheduleData, writer);
                    writer.write("\n]");
                }

                System.out.println("=== SCHEDULE SAVED ===");
                System.out.println("Schedule ID: " + scheduleData.get("scheduleId"));
                System.out.println("Route: " + scheduleData.get("fromStation") + " -> " +
                        scheduleData.get("toStation"));
                System.out.println("Cargo: " + scheduleData.get("cargoId"));
                System.out.println("Wagons: " + scheduleData.get("wagonIds"));
                System.out.println("Locomotive: " + scheduleData.get("locomotiveId"));
                System.out.println("Train available at: " + scheduleData.get("trainAvailableTime"));
                System.out.println("Departure: " + scheduleData.get("departureTime"));
                System.out.println("Arrival: " + scheduleData.get("arrivalTime"));
                System.out.println("======================");

            } catch (IOException e) {
                System.err.println(agentId + ": Error saving schedule: " + e.getMessage());
            }
        }
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated");
    }
}