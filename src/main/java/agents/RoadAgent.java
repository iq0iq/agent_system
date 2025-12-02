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
import java.util.concurrent.ConcurrentHashMap;

public class RoadAgent extends Agent {
    private String agentId;
    private Route route;
    private ScheduleData scheduleData;
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Map<String, Map<String, Object>> confirmedSchedules = new ConcurrentHashMap<>();

    private class LocomotiveRequest {
        String cargoId;
        String fromStation;
        String toStation;
        double weight;
        String locomotiveId;
        String wagonId;
        String priority;
        ACLMessage originalMessage;

        LocomotiveRequest(String cargoId, String fromStation, String toStation,
                          double weight, String locomotiveId, String wagonId,
                          String priority, ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.weight = weight;
            this.locomotiveId = locomotiveId;
            this.wagonId = wagonId;
            this.priority = priority;
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
        addBehaviour(new BookingConfirmationBehaviour());
        addBehaviour(new FinalConfirmationBehaviour());
        System.out.println(agentId + " started for route: " + route.getFromStation() +
                " -> " + route.getToStation() + " (" + route.getDistance() + " km)");
    }

    private class LocomotiveRequestBehaviour extends CyclicBehaviour {
        public void action() {
            // Ждем запросы от локомотивов
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
            double weight = Double.parseDouble(parts[3]);
            String locomotiveId = parts[4];
            String wagonId = parts[5];
            String priority = parts[6];

            currentRequest = new LocomotiveRequest(cargoId, fromStation, toStation, weight,
                    locomotiveId, wagonId, priority, msg);

            // Проверяем, подходит ли наш маршрут
            if (!route.getFromStation().equals(fromStation) || !route.getToStation().equals(toStation)) {
                System.out.println(agentId + ": Route mismatch. Our: " + route.getFromStation() +
                        "->" + route.getToStation() + ", Requested: " + fromStation + "->" + toStation);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("ROUTE_MISMATCH");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            // Рассчитываем продолжительность поездки
            int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());

            // Находим доступное время
            Date availableTime = scheduleData.findNearestAvailableTime(tripDuration);

            // Рассчитываем стоимость
            double baseCost = route.getDistance() * 10.0; // 10 за км

            // Учитываем приоритет (HIGH приоритет дороже)
            double priorityMultiplier = 1.0;
            if ("HIGH".equals(priority)) {
                priorityMultiplier = 1.5;
            } else if ("LOW".equals(priority)) {
                priorityMultiplier = 0.8;
            }

            double totalCost = baseCost * priorityMultiplier;

            // Отправляем предложение локомотиву
            ACLMessage reply = currentRequest.originalMessage.createReply();
            reply.setPerformative(ACLMessage.PROPOSE);
            reply.setContent(availableTime.getTime() + ":" + totalCost + ":" +
                    "ROUTE_" + fromStation + "_" + toStation);
            myAgent.send(reply);

            System.out.println(agentId + ": Sent proposal to " + msg.getSender().getLocalName() +
                    " - time: " + availableTime + ", cost: " + totalCost +
                    ", duration: " + tripDuration + " min");

            // Сбрасываем текущий запрос, так как ответ отправлен
            currentRequest = null;
        }
    }

    private class BookingConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                // Получили подтверждение от локомотива
                String content = msg.getContent();
                if (content.startsWith("ACCEPT_PROPOSAL:")) {
                    String cargoId = content.substring("ACCEPT_PROPOSAL:".length());

                    // Создаем расписание
                    String scheduleId = "SCHEDULE_" + System.currentTimeMillis();

                    // Рассчитываем время поездки (на основе предложения, которое мы отправили ранее)
                    // Для простоты будем считать, что поездка начнется через 1 час
                    int tripDuration = TimeUtils.calculateTripDuration(route.getDistance());
                    Date startTime = TimeUtils.addMinutes(new Date(), 60);
                    Date endTime = TimeUtils.addMinutes(startTime, tripDuration);

                    // Резервируем время в расписании
                    scheduleData.reserveTimeSlot(startTime, endTime);

                    // Создаем и сохраняем расписание
                    createSchedule(scheduleId, cargoId, startTime, endTime);

                    System.out.println(agentId + ": Schedule created: " + scheduleId +
                            " for cargo: " + cargoId);

                    // Уведомляем локомотив о создании расписания
                    ACLMessage confirmMsg = new ACLMessage(ACLMessage.CONFIRM);
                    confirmMsg.addReceiver(msg.getSender());
                    confirmMsg.setContent("SCHEDULE_CREATED:" + scheduleId);
                    myAgent.send(confirmMsg);
                }
            } else {
                block();
            }
        }

        private void createSchedule(String scheduleId, String cargoId, Date startTime, Date endTime) {
            Map<String, Object> scheduleData = new HashMap<>();
            scheduleData.put("scheduleId", scheduleId);
            scheduleData.put("routeId", "ROUTE_" + route.getFromStation() + "_" + route.getToStation());
            scheduleData.put("fromStation", route.getFromStation());
            scheduleData.put("toStation", route.getToStation());
            scheduleData.put("distance", route.getDistance());
            scheduleData.put("cargoId", cargoId);
            scheduleData.put("startTime", startTime);
            scheduleData.put("endTime", endTime);
            scheduleData.put("duration", (endTime.getTime() - startTime.getTime()) / 60000 + " min");
            scheduleData.put("status", "CONFIRMED");
            scheduleData.put("creationTime", new Date());

            confirmedSchedules.put(scheduleId, scheduleData);
            saveScheduleToFile(scheduleData);
        }

        private void saveScheduleToFile(Map<String, Object> scheduleData) {
            try {
                String filename = "schedules.json";
                try (FileWriter writer = new FileWriter(filename, true)) {
                    gson.toJson(scheduleData, writer);
                    writer.write(",\n");
                }

                System.out.println("=== SCHEDULE SAVED ===");
                System.out.println("Schedule ID: " + scheduleData.get("scheduleId"));
                System.out.println("Route: " + scheduleData.get("fromStation") + " -> " +
                        scheduleData.get("toStation"));
                System.out.println("Distance: " + scheduleData.get("distance") + " km");
                System.out.println("Cargo ID: " + scheduleData.get("cargoId"));
                System.out.println("Time: " + scheduleData.get("startTime") + " to " +
                        scheduleData.get("endTime"));
                System.out.println("Duration: " + scheduleData.get("duration"));
                System.out.println("======================");

            } catch (IOException e) {
                System.err.println(agentId + ": Error saving schedule: " + e.getMessage());
            }
        }
    }

    private class FinalConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_FINALIZED:")) {
                    String scheduleId = content.substring("SCHEDULE_FINALIZED:".length());
                    System.out.println(agentId + ": Schedule finalized: " + scheduleId);
                }
            } else {
                block();
            }
        }
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated");
    }
}