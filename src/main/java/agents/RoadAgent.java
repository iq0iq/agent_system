package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
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

    private Map<String, LocomotiveRequest> pendingRequests = new HashMap<>();

    private class LocomotiveRequest {
        String cargoIds;
        String fromStation;
        String toStation;
        double totalWeight;
        String locomotiveId;
        String wagonIds;
        Date trainAvailableTime;
        ACLMessage originalMessage;
        long requestTime;
        boolean isProcessed;
        Date proposedTime;

        LocomotiveRequest(String cargoIds, String fromStation, String toStation,
                          double totalWeight, String locomotiveId, String wagonIds,
                          Date trainAvailableTime, ACLMessage originalMessage) {
            this.cargoIds = cargoIds;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.totalWeight = totalWeight;
            this.locomotiveId = locomotiveId;
            this.wagonIds = wagonIds;
            this.trainAvailableTime = trainAvailableTime;
            this.originalMessage = originalMessage;
            this.requestTime = System.currentTimeMillis();
            this.isProcessed = false;
            this.proposedTime = null;
        }

        String getRequestId() {
            return locomotiveId + "_" + wagonIds + "_" + trainAvailableTime.getTime();
        }
    }

    protected void setup() {
        try {
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
            } catch (FIPAException e) {
                System.err.println(agentId + ": Error registering with DF: " + e.getMessage());
            }

            addBehaviour(new LocomotiveRequestBehaviour(this, 100));
            addBehaviour(new AcceptProposalBehaviour(this, 100));
            addBehaviour(new RequestCleanupBehaviour(this, 60000));

            System.out.println(agentId + " started for route: " + route.getFromStation() +
                    " -> " + route.getToStation());
        } catch (Exception e) {
            System.err.println(agentId + ": Error in setup: " + e.getMessage());
            e.printStackTrace();
            doDelete();
        }
    }

    private class LocomotiveRequestBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);

        public LocomotiveRequestBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            try {
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content.startsWith("LOCOMOTIVE_REQUEST:")) {
                        handleLocomotiveRequest(msg, content);
                    }
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error in LocomotiveRequestBehaviour: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void handleLocomotiveRequest(ACLMessage msg, String content) {
            try {
                String[] parts = content.substring("LOCOMOTIVE_REQUEST:".length()).split(":");

                if (parts.length < 7) {
                    System.err.println(agentId + ": Invalid LOCOMOTIVE_REQUEST format. Expected 7 parts, got " + parts.length);
                    System.err.println("Content: " + content);
                    sendRefusal(msg, "INVALID_REQUEST_FORMAT");
                    return;
                }

                String cargoIds = parts[0];
                String fromStation = parts[1];
                String toStation = parts[2];
                double totalWeight = Double.parseDouble(parts[3]);
                String locomotiveId = parts[4];
                String wagonIds = parts[5];
                Date trainAvailableTime = new Date(Long.parseLong(parts[6]));

                if (!route.getFromStation().equals(fromStation) || !route.getToStation().equals(toStation)) {
                    System.out.println(agentId + ": Route mismatch for request from " +
                            fromStation + " to " + toStation);
                    sendRefusal(msg, "ROUTE_MISMATCH");
                    return;
                }

                LocomotiveRequest request = new LocomotiveRequest(cargoIds, fromStation, toStation,
                        totalWeight, locomotiveId, wagonIds, trainAvailableTime, msg);

                String requestId = request.getRequestId();
                pendingRequests.put(requestId, request);

                System.out.println(agentId + ": Received request for cargoes " + cargoIds +
                        " from locomotive " + locomotiveId + " with wagons " + wagonIds +
                        ", requested time: " + trainAvailableTime);

                processRequest(request);
            } catch (NumberFormatException e) {
                System.err.println(agentId + ": Error parsing number in LOCOMOTIVE_REQUEST: " + e.getMessage());
                sendRefusal(msg, "INVALID_NUMBER_FORMAT");
            } catch (Exception e) {
                System.err.println(agentId + ": Error handling locomotive request: " + e.getMessage());
                e.printStackTrace();
                sendRefusal(msg, "INTERNAL_ERROR");
            }
        }

        private void processRequest(LocomotiveRequest request) {
            try {
                int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());

                Date availableTime = scheduleData.findNearestAvailableTimeAfter(
                        request.trainAvailableTime, tripDuration);

                Date optimizedTime = optimizeScheduleForComposition(request, availableTime, tripDuration);

                request.proposedTime = optimizedTime;

                ACLMessage reply = request.originalMessage.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(optimizedTime.getTime() + ":" +
                        "ROUTE_" + request.fromStation + "_" + request.toStation + ":" +
                        request.cargoIds);
                myAgent.send(reply);

                System.out.println(agentId + ": Sent proposal to " +
                        request.originalMessage.getSender().getLocalName() +
                        " - requested time: " + request.trainAvailableTime +
                        ", proposed time: " + optimizedTime +
                        ", duration: " + tripDuration + " min" +
                        ", cargoes: " + request.cargoIds);

                request.isProcessed = true;
            } catch (Exception e) {
                System.err.println(agentId + ": Error processing request: " + e.getMessage());
                e.printStackTrace();
                sendRefusal(request.originalMessage, "ERROR_PROCESSING_REQUEST");
            }
        }

        private void sendRefusal(ACLMessage msg, String reason) {
            try {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent(reason);
                myAgent.send(reply);
            } catch (Exception e) {
                System.err.println(agentId + ": Error sending refusal: " + e.getMessage());
            }
        }

        private Date optimizeScheduleForComposition(LocomotiveRequest newRequest, Date suggestedTime, int tripDuration) {
            try {
                List<TimeSlot> schedule = scheduleData.getSchedule();

                for (TimeSlot slot : schedule) {
                    if (!slot.isAvailable() && slot.getEndTime().after(newRequest.trainAvailableTime)) {
                        Date potentialStart = slot.getEndTime();
                        long delay = (potentialStart.getTime() - newRequest.trainAvailableTime.getTime()) / 60000;

                        if (delay <= 30) {
                            Date potentialEnd = TimeUtils.addMinutes(potentialStart, tripDuration);

                            boolean conflicts = false;
                            for (TimeSlot otherSlot : schedule) {
                                if (otherSlot != slot &&
                                        potentialStart.before(otherSlot.getEndTime()) &&
                                        potentialEnd.after(otherSlot.getStartTime())) {
                                    conflicts = true;
                                    break;
                                }
                            }

                            if (!conflicts) {
                                System.out.println(agentId + ": Optimized schedule - adding composition after existing slot at " + potentialStart);
                                return potentialStart;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error in optimizeScheduleForComposition: " + e.getMessage());
            }

            return suggestedTime;
        }
    }

    private class AcceptProposalBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);

        public AcceptProposalBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            try {
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content.startsWith("ACCEPT_PROPOSAL:")) {
                        handleAcceptProposal(msg, content);
                    }
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error in AcceptProposalBehaviour: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void handleAcceptProposal(ACLMessage msg, String content) {
            try {
                String[] parts = content.substring("ACCEPT_PROPOSAL:".length()).split(":");

                if (parts.length < 3) {
                    System.err.println(agentId + ": Invalid ACCEPT_PROPOSAL format. Expected 3 parts, got " + parts.length);
                    return;
                }

                String cargoIds = parts[0];
                String toStation = parts[1];
                Date requestedDepartureTime = new Date(Long.parseLong(parts[2]));

                LocomotiveRequest request = findRequestByCargoIds(cargoIds);

                if (request == null) {
                    System.err.println(agentId + ": No request found for cargoes " + cargoIds);
                    return;
                }

                if (!request.toStation.equals(toStation)) {
                    System.err.println(agentId + ": Station mismatch for cargoes " + cargoIds);
                    return;
                }

                int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());

                Date departureTime;
                if (requestedDepartureTime != null &&
                        scheduleData.canAddTimeSlot(requestedDepartureTime,
                                TimeUtils.addMinutes(requestedDepartureTime, tripDuration))) {
                    departureTime = requestedDepartureTime;
                } else if (request.proposedTime != null) {
                    departureTime = request.proposedTime;
                } else {
                    departureTime = scheduleData.findNearestAvailableTimeAfter(
                            request.trainAvailableTime, tripDuration);
                }

                Date arrivalTime = TimeUtils.addMinutes(departureTime, tripDuration);

                if (!scheduleData.reserveTimeSlot(departureTime, arrivalTime)) {
                    ACLMessage rejectMsg = msg.createReply();
                    rejectMsg.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    rejectMsg.setContent("TIME_SLOT_UNAVAILABLE");
                    myAgent.send(rejectMsg);
                    System.out.println(agentId + ": Time slot unavailable for request: " + cargoIds);
                    return;
                }

                String scheduleId = "SCHEDULE_" + System.currentTimeMillis() + "_" +
                        request.locomotiveId + "_" + request.wagonIds.hashCode();

                createSchedule(scheduleId, departureTime, arrivalTime, tripDuration, request);

                System.out.println("✅ " + agentId + ": Schedule FINALIZED: " + scheduleId +
                        " for locomotive: " + request.locomotiveId +
                        ", wagons: " + request.wagonIds +
                        ", cargoes: " + request.cargoIds +
                        ", departure: " + departureTime +
                        ", arrival: " + arrivalTime);

                // 1. Отправляем подтверждение локомотиву
                ACLMessage locomotiveConfirmMsg = new ACLMessage(ACLMessage.INFORM);
                locomotiveConfirmMsg.addReceiver(msg.getSender());
                locomotiveConfirmMsg.setContent("SCHEDULE_FINALIZED:" + scheduleId + ":" +
                        departureTime.getTime() + ":" + arrivalTime.getTime() + ":" +
                        request.wagonIds + ":" + request.cargoIds);
                myAgent.send(locomotiveConfirmMsg);
                System.out.println(agentId + ": Sent FINALIZED notification to locomotive " +
                        request.locomotiveId);

                // 2. Отправляем подтверждение всем вагонам и грузам
                String[] wagonIdsArray = request.wagonIds.split(",");
                String[] cargoIdsArray = request.cargoIds.split(",");

                for (int i = 0; i < wagonIdsArray.length; i++) {
                    String wagonId = wagonIdsArray[i]; // Например "W2"
                    String cargoId = (i < cargoIdsArray.length) ? cargoIdsArray[i] : ""; // Например "C2"

                    // ИСПРАВЛЕНИЕ: Преобразуем W2 -> WagonAgent2
                    String wagonAgentName = "WagonAgent" + wagonId.substring(1);

                    // Вагону
                    ACLMessage wagonConfirmMsg = new ACLMessage(ACLMessage.INFORM);
                    wagonConfirmMsg.addReceiver(new jade.core.AID(wagonAgentName, jade.core.AID.ISLOCALNAME));
                    wagonConfirmMsg.setContent("SCHEDULE_FINALIZED:" + scheduleId + ":" +
                            departureTime.getTime() + ":" + arrivalTime.getTime() + ":" +
                            cargoId);
                    myAgent.send(wagonConfirmMsg);
                    System.out.println(agentId + ": Sent FINALIZED notification to wagon agent " +
                            wagonAgentName + " (wagonId: " + wagonId + ") for cargo " + cargoId);

                    // Грузу (если указан)
                    if (!cargoId.isEmpty()) {
                        String cargoAgentName = "CargoAgent" + cargoId.substring(1);

                        ACLMessage cargoConfirmMsg = new ACLMessage(ACLMessage.INFORM);
                        cargoConfirmMsg.addReceiver(new jade.core.AID(cargoAgentName, jade.core.AID.ISLOCALNAME));
                        cargoConfirmMsg.setContent("SCHEDULE_FINALIZED:" + scheduleId + ":" +
                                departureTime.getTime() + ":" + arrivalTime.getTime());
                        myAgent.send(cargoConfirmMsg);
                        System.out.println(agentId + ": Sent FINALIZED notification to cargo agent " +
                                cargoAgentName + " (cargoId: " + cargoId + ")");
                    }
                }

                pendingRequests.remove(request.getRequestId());
            } catch (NumberFormatException e) {
                System.err.println(agentId + ": Error parsing number in ACCEPT_PROPOSAL: " + e.getMessage());
            } catch (Exception e) {
                System.err.println(agentId + ": Error handling ACCEPT_PROPOSAL: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private LocomotiveRequest findRequestByCargoIds(String cargoIds) {
            for (LocomotiveRequest request : pendingRequests.values()) {
                if (request.cargoIds.equals(cargoIds) && request.isProcessed) {
                    return request;
                }
            }
            return null;
        }

        private void createSchedule(String scheduleId, Date departureTime, Date arrivalTime,
                                    int tripDuration, LocomotiveRequest request) {
            try {
                Map<String, Object> scheduleDataMap = new HashMap<>();
                scheduleDataMap.put("scheduleId", scheduleId);
                scheduleDataMap.put("fromStation", route.getFromStation());
                scheduleDataMap.put("toStation", route.getToStation());
                scheduleDataMap.put("distance", route.getDistance());
                scheduleDataMap.put("cargoIds", request.cargoIds);
                scheduleDataMap.put("wagonIds", request.wagonIds);
                scheduleDataMap.put("locomotiveId", request.locomotiveId);
                scheduleDataMap.put("totalWeight", request.totalWeight);
                scheduleDataMap.put("trainAvailableTime", request.trainAvailableTime);
                scheduleDataMap.put("departureTime", departureTime);
                scheduleDataMap.put("arrivalTime", arrivalTime);
                scheduleDataMap.put("duration", tripDuration + " min");
                scheduleDataMap.put("compositionSize", request.wagonIds.split(",").length);
                scheduleDataMap.put("creationTime", new Date());
                scheduleDataMap.put("roadAgent", agentId);

                saveScheduleToFile(scheduleDataMap);
            } catch (Exception e) {
                System.err.println(agentId + ": Error creating schedule: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void saveScheduleToFile(Map<String, Object> scheduleData) {
            try {
                String filename = "schedules.json";
                java.io.File file = new java.io.File(filename);
                boolean fileExists = file.exists();

                List<Map<String, Object>> schedules = new ArrayList<>();

                if (fileExists && file.length() > 0) {
                    try (java.io.FileReader reader = new java.io.FileReader(file)) {
                        schedules = gson.fromJson(reader, List.class);
                        if (schedules == null) {
                            schedules = new ArrayList<>();
                        }
                    } catch (Exception e) {
                        System.err.println(agentId + ": Error reading existing schedules, starting fresh: " + e.getMessage());
                        schedules = new ArrayList<>();
                    }
                }

                schedules.add(scheduleData);

                try (FileWriter writer = new FileWriter(filename)) {
                    gson.toJson(schedules, writer);
                }

                System.out.println("=== SCHEDULE SAVED ===");
                System.out.println("Schedule ID: " + scheduleData.get("scheduleId"));
                System.out.println("Route: " + scheduleData.get("fromStation") + " -> " +
                        scheduleData.get("toStation"));
                System.out.println("Cargoes: " + scheduleData.get("cargoIds"));
                System.out.println("Wagons: " + scheduleData.get("wagonIds"));
                System.out.println("Locomotive: " + scheduleData.get("locomotiveId"));
                System.out.println("Composition size: " + scheduleData.get("compositionSize"));
                System.out.println("Train available at: " + scheduleData.get("trainAvailableTime"));
                System.out.println("Departure: " + scheduleData.get("departureTime"));
                System.out.println("Arrival: " + scheduleData.get("arrivalTime"));
                System.out.println("======================");

            } catch (IOException e) {
                System.err.println(agentId + ": Error saving schedule to file: " + e.getMessage());
            } catch (Exception e) {
                System.err.println(agentId + ": Unexpected error saving schedule: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private class RequestCleanupBehaviour extends TickerBehaviour {
        public RequestCleanupBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            try {
                long currentTime = System.currentTimeMillis();
                List<String> toRemove = new ArrayList<>();

                for (Map.Entry<String, LocomotiveRequest> entry : pendingRequests.entrySet()) {
                    LocomotiveRequest request = entry.getValue();

                    if ((currentTime - request.requestTime) > 300000) {
                        System.out.println(agentId + ": Removing old request for cargoes " +
                                request.cargoIds);
                        toRemove.add(entry.getKey());
                    }
                }

                for (String key : toRemove) {
                    pendingRequests.remove(key);
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error in RequestCleanupBehaviour: " + e.getMessage());
            }
        }
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error deregistering from DF: " + e.getMessage());
        } catch (Exception e) {
            System.err.println(agentId + ": Unexpected error in takeDown: " + e.getMessage());
        }
        System.out.println(agentId + " terminated");
    }
}