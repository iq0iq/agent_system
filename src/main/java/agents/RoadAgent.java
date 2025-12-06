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

                // Получаем скорость локомотива (если передана, иначе используем 60 км/ч по умолчанию)
                double locomotiveSpeed = 60.0; // значение по умолчанию
                if (parts.length >= 8) {
                    try {
                        locomotiveSpeed = Double.parseDouble(parts[7]);
                    } catch (NumberFormatException e) {
                        System.out.println(agentId + ": Invalid locomotive speed format, using default 60 km/h");
                    }
                }

                if (!route.getFromStation().equals(fromStation) || !route.getToStation().equals(toStation)) {
                    System.out.println(agentId + ": Route mismatch for request from " +
                            fromStation + " to " + toStation);
                    sendRefusal(msg, "ROUTE_MISMATCH");
                    return;
                }

                // Рассчитываем время поездки на основе скорости локомотива
                int tripDuration = TimeUtils.calculateTripDuration(route.getDistance(), locomotiveSpeed);

                // Находим ближайшее доступное время
                Date availableTime = scheduleData.findNearestAvailableTimeAfter(trainAvailableTime, tripDuration);

                // Отправляем предложение
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(availableTime.getTime() + ":" +
                        "ROUTE_" + fromStation + "_" + toStation + ":" +
                        cargoIds);
                myAgent.send(reply);

                System.out.println(agentId + ": Sent proposal to " +
                        msg.getSender().getLocalName() +
                        " - requested time: " + trainAvailableTime +
                        ", proposed time: " + availableTime +
                        ", duration: " + tripDuration + " min" +
                        ", locomotive speed: " + locomotiveSpeed + " km/h" +
                        ", cargoes: " + cargoIds);
            } catch (NumberFormatException e) {
                System.err.println(agentId + ": Error parsing number in LOCOMOTIVE_REQUEST: " + e.getMessage());
                sendRefusal(msg, "INVALID_NUMBER_FORMAT");
            } catch (Exception e) {
                System.err.println(agentId + ": Error handling locomotive request: " + e.getMessage());
                e.printStackTrace();
                sendRefusal(msg, "INTERNAL_ERROR");
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

                // Новый формат ACCEPT_PROPOSAL должен содержать все необходимые данные:
                // ACCEPT_PROPOSAL:cargoIds:fromStation:toStation:totalWeight:locomotiveId:wagonIds:trainAvailableTime:locomotiveSpeed:requestedDepartureTime

                if (parts.length < 9) {
                    System.err.println(agentId + ": Invalid ACCEPT_PROPOSAL format. Expected 9 parts, got " + parts.length);
                    System.err.println("Content: " + content);
                    return;
                }

                String cargoIds = parts[0];
                String fromStation = parts[1];
                String toStation = parts[2];
                double totalWeight = Double.parseDouble(parts[3]);
                String locomotiveId = parts[4];
                String wagonIds = parts[5];
                Date trainAvailableTime = new Date(Long.parseLong(parts[6]));
                double locomotiveSpeed = Double.parseDouble(parts[7]);
                Date requestedDepartureTime = new Date(Long.parseLong(parts[8]));

                if (!route.getFromStation().equals(fromStation) || !route.getToStation().equals(toStation)) {
                    System.err.println(agentId + ": Route mismatch for cargoes " + cargoIds);
                    return;
                }

                // Рассчитываем длительность поездки на основе скорости локомотива
                int tripDuration = TimeUtils.calculateTripDuration(route.getDistance(), locomotiveSpeed);

                Date departureTime;
                Date arrivalTime;

                // Проверяем, доступен ли запрошенный слот
                if (scheduleData.canAddTimeSlot(requestedDepartureTime,
                        TimeUtils.addMinutes(requestedDepartureTime, tripDuration))) {
                    departureTime = requestedDepartureTime;
                } else {
                    // Ищем ближайшее доступное время
                    departureTime = scheduleData.findNearestAvailableTimeAfter(trainAvailableTime, tripDuration);
                }

                arrivalTime = TimeUtils.addMinutes(departureTime, tripDuration);

                // Резервируем слот
                if (!scheduleData.reserveTimeSlot(departureTime, arrivalTime)) {
                    ACLMessage rejectMsg = msg.createReply();
                    rejectMsg.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    rejectMsg.setContent("TIME_SLOT_UNAVAILABLE");
                    myAgent.send(rejectMsg);
                    sendRejectionsToAll(locomotiveId, wagonIds, cargoIds, msg.getSender(), "TIME_SLOT_UNAVAILABLE");
                    System.out.println(agentId + ": Time slot unavailable for request: " + cargoIds);
                    return;
                }

                String scheduleId = "SCHEDULE_" + System.currentTimeMillis() + "_" +
                        locomotiveId + "_" + wagonIds.hashCode();

                createSchedule(scheduleId, departureTime, arrivalTime, tripDuration,
                        cargoIds, wagonIds, locomotiveId, locomotiveSpeed,
                        trainAvailableTime, totalWeight);

                System.out.println("✅ " + agentId + ": Schedule FINALIZED: " + scheduleId +
                        " for locomotive: " + locomotiveId +
                        ", wagons: " + wagonIds +
                        ", cargoes: " + cargoIds +
                        ", departure: " + departureTime +
                        ", arrival: " + arrivalTime +
                        ", locomotive speed: " + locomotiveSpeed + " km/h" +
                        ", trip duration: " + tripDuration + " min");

                // 1. Отправляем подтверждение локомотиву
                ACLMessage locomotiveConfirmMsg = new ACLMessage(ACLMessage.INFORM);
                locomotiveConfirmMsg.addReceiver(msg.getSender());
                locomotiveConfirmMsg.setContent("SCHEDULE_FINALIZED:" + scheduleId + ":" +
                        departureTime.getTime() + ":" + arrivalTime.getTime() + ":" +
                        wagonIds + ":" + cargoIds);
                myAgent.send(locomotiveConfirmMsg);
                System.out.println(agentId + ": Sent FINALIZED notification to locomotive " +
                        locomotiveId);

                // 2. Отправляем подтверждение всем вагонам и грузам
                String[] wagonIdsArray = wagonIds.split(",");
                String[] cargoIdsArray = cargoIds.split(",");

                for (int i = 0; i < wagonIdsArray.length; i++) {
                    String wagonId = wagonIdsArray[i];
                    String cargoId = (i < cargoIdsArray.length) ? cargoIdsArray[i] : "";

                    // Преобразуем W2 -> WagonAgent2
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

            } catch (NumberFormatException e) {
                System.err.println(agentId + ": Error parsing number in ACCEPT_PROPOSAL: " + e.getMessage());
            } catch (Exception e) {
                System.err.println(agentId + ": Error handling ACCEPT_PROPOSAL: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void sendRejectionsToAll(String locomotiveId, String wagonIds, String cargoIds,
                                         jade.core.AID locomotiveAID, String reason) {
            try {
                // 1. Отправляем отказ локомотиву (уже отправлен выше)

                // 2. Отправляем отказ всем вагонам
                String[] wagonIdsArray = wagonIds.split(",");
                String[] cargoIdsArray = cargoIds.split(",");

                for (int i = 0; i < wagonIdsArray.length; i++) {
                    String wagonId = wagonIdsArray[i];
                    String cargoId = (i < cargoIdsArray.length) ? cargoIdsArray[i] : "";

                    // Преобразуем W2 -> WagonAgent2
                    String wagonAgentName = "WagonAgent" + wagonId.substring(1);

                    // Вагону
                    ACLMessage wagonRejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                    wagonRejectMsg.addReceiver(new jade.core.AID(wagonAgentName, jade.core.AID.ISLOCALNAME));
                    wagonRejectMsg.setContent("ROAD_REJECTED:" + reason + ":" + cargoId + ":" + locomotiveId);
                    myAgent.send(wagonRejectMsg);
                    System.out.println(agentId + ": Sent REJECTION notification to wagon agent " +
                            wagonAgentName + " (wagonId: " + wagonId + ") for cargo " + cargoId);

                    // Грузу (если указан)
                    if (!cargoId.isEmpty()) {
                        String cargoAgentName = "CargoAgent" + cargoId.substring(1);

                        ACLMessage cargoRejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        cargoRejectMsg.addReceiver(new jade.core.AID(cargoAgentName, jade.core.AID.ISLOCALNAME));
                        cargoRejectMsg.setContent("ROAD_REJECTED:" + reason + ":" + locomotiveId);
                        myAgent.send(cargoRejectMsg);
                        System.out.println(agentId + ": Sent REJECTION notification to cargo agent " +
                                cargoAgentName + " (cargoId: " + cargoId + ")");
                    }
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error sending rejections: " + e.getMessage());
            }
        }

        private void createSchedule(String scheduleId, Date departureTime, Date arrivalTime,
                                    int tripDuration, String cargoIds, String wagonIds,
                                    String locomotiveId, double locomotiveSpeed,
                                    Date trainAvailableTime, double totalWeight) {
            try {
                Map<String, Object> scheduleDataMap = new HashMap<>();
                scheduleDataMap.put("scheduleId", scheduleId);
                scheduleDataMap.put("fromStation", route.getFromStation());
                scheduleDataMap.put("toStation", route.getToStation());
                scheduleDataMap.put("cargoIds", cargoIds);
                scheduleDataMap.put("wagonIds", wagonIds);
                scheduleDataMap.put("locomotiveId", locomotiveId);
                scheduleDataMap.put("locomotiveSpeed", locomotiveSpeed);
                scheduleDataMap.put("trainAvailableTime", trainAvailableTime);
                scheduleDataMap.put("totalWeight", totalWeight);
                scheduleDataMap.put("departureTime", departureTime);
                scheduleDataMap.put("arrivalTime", arrivalTime);
                scheduleDataMap.put("duration", tripDuration);
                scheduleDataMap.put("roadAgent", agentId);

                saveScheduleToFile(scheduleDataMap);
            } catch (Exception e) {
                System.err.println(agentId + ": Error creating schedule: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private synchronized void saveScheduleToFile(Map<String, Object> scheduleData) {
            try {
                String filename = "schedules.json";
                java.io.File file = new java.io.File(filename);

                List<Map<String, Object>> schedules = new ArrayList<>();

                // Блокировка на уровне метода для всех агентов в JVM
                synchronized (RoadAgent.class) {
                    if (file.exists() && file.length() > 0) {
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
                }

                System.out.println("=== SCHEDULE SAVED ===");
                System.out.println("Schedule ID: " + scheduleData.get("scheduleId"));
                System.out.println("Route: " + scheduleData.get("fromStation") + " -> " +
                        scheduleData.get("toStation"));
                System.out.println("Cargoes: " + scheduleData.get("cargoIds"));
                System.out.println("Wagons: " + scheduleData.get("wagonIds"));
                System.out.println("Locomotive: " + scheduleData.get("locomotiveId"));
                System.out.println("Locomotive Speed: " + scheduleData.get("locomotiveSpeed") + " km/h");
                System.out.println("Total Weight: " + scheduleData.get("totalWeight") + " tons");
                System.out.println("Train available at: " + scheduleData.get("trainAvailableTime"));
                System.out.println("Departure: " + scheduleData.get("departureTime"));
                System.out.println("Arrival: " + scheduleData.get("arrivalTime"));
                System.out.println("Duration: " + scheduleData.get("duration") + " min");
                System.out.println("======================");

            } catch (IOException e) {
                System.err.println(agentId + ": Error saving schedule to file: " + e.getMessage());
            } catch (Exception e) {
                System.err.println(agentId + ": Unexpected error saving schedule: " + e.getMessage());
                e.printStackTrace();
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