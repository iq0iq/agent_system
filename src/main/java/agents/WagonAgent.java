package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import models.Wagon;
import models.ScheduleData;
import models.Proposal;
import models.TimeSlot;
import utils.DataLoader;
import utils.TimeUtils;
import java.util.*;

public class WagonAgent extends Agent {
    private Set<String> processedCargoRequests = new HashSet<>();
    private Wagon wagon;
    private String agentId;
    private ScheduleData scheduleData;
    private Map<String, Proposal> locomotiveProposals = new HashMap<>();
    private List<String> locomotiveAgentsContacted = new ArrayList<>();
    private int expectedLocomotiveResponses = 0;
    private Proposal bestLocomotiveProposal = null;
    private String currentCargoId = null;
    private String currentCargoToStation = null;
    private long startTime;

    private Map<String, CargoRequest> pendingRequests = new HashMap<>();
    private final long RETRY_INTERVAL = 4000;
    private final int MAX_RETRIES = 3;
    private final long REQUEST_TIMEOUT = 600000;

    private class CargoRequest {
        String cargoId;
        String cargoType;
        double weight;
        String fromStation;
        String toStation;
        Date wagonAvailableTime;
        ACLMessage originalMessage;
        int attemptCount;
        long lastAttemptTime;
        long creationTime;
        boolean isActive;
        Proposal bestProposal;

        CargoRequest(String cargoId, String cargoType, double weight,
                     String fromStation, String toStation,
                     Date wagonAvailableTime, ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.cargoType = cargoType;
            this.weight = weight;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.wagonAvailableTime = wagonAvailableTime;
            this.originalMessage = originalMessage;
            this.attemptCount = 1;
            this.lastAttemptTime = System.currentTimeMillis();
            this.creationTime = System.currentTimeMillis();
            this.isActive = true;
        }

        boolean canRetry() {
            return attemptCount < MAX_RETRIES &&
                    (System.currentTimeMillis() - lastAttemptTime) > RETRY_INTERVAL &&
                    isActive &&
                    (System.currentTimeMillis() - creationTime) < REQUEST_TIMEOUT;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - creationTime) > REQUEST_TIMEOUT;
        }
    }

    protected void setup() {
        agentId = (String) getArguments()[0];
        wagon = DataLoader.getWagonForAgent(agentId);

        if (wagon == null) {
            System.out.println(agentId + ": No wagon found!");
            doDelete();
            return;
        }

        scheduleData = new ScheduleData(wagon.getId());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("wagon");
        sd.setName("WagonService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error registering with DF: " + e.getMessage());
        }

        addBehaviour(new CargoRequestBehaviour(this, 100));
        addBehaviour(new WaitForLocomotiveResponsesBehaviour(this, 100));
        addBehaviour(new AcceptProposalBehaviour(this, 100));
        addBehaviour(new RetryManagerBehaviour(this, 10000));

        System.out.println(agentId + " started with wagon: " + wagon.getId() +
                " at station: " + wagon.getCurrentStation());
    }

    private class CargoRequestBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);

        public CargoRequestBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("CARGO_REQUEST:")) {
                    handleCargoRequest(msg, content);
                }
            }
        }

        private void handleCargoRequest(ACLMessage msg, String content) {
            String[] parts = content.substring("CARGO_REQUEST:".length()).split(":");
            String cargoId = parts[0];
            String cargoType = parts[1];
            double weight = Double.parseDouble(parts[2]);
            String fromStation = parts[3];
            String toStation = parts[4];
            Date cargoAppearanceTime = new Date(Long.parseLong(parts[5]));

            String requestKey = cargoId + "_" + agentId;

            Date wagonAvailableTime = calculateWagonAvailableTime(cargoAppearanceTime);

            if (pendingRequests.containsKey(cargoId)) {
                CargoRequest existingRequest = pendingRequests.get(cargoId);
                if (existingRequest.isActive) {
                    System.out.println(agentId + ": Already processing cargo " + cargoId);
                    return;
                }
            }

            CargoRequest request = new CargoRequest(cargoId, cargoType, weight, fromStation,
                    toStation, wagonAvailableTime, msg);

            pendingRequests.put(cargoId, request);

            if (!wagon.canCarryCargo(cargoType, weight)) {
                System.out.println(agentId + ": Cannot carry cargo " + cargoType + " weight " + weight);
                sendRefusal(msg, "INCOMPATIBLE_CARGO_TYPE_OR_WEIGHT", cargoId);
                request.isActive = false;
                return;
            }

            if (!wagon.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Not at requested station. Current: " +
                        wagon.getCurrentStation() + ", Requested: " + fromStation);
                sendRefusal(msg, "DIFFERENT_STATION", cargoId);
                request.isActive = false;
                return;
            }

            System.out.println(agentId + ": Cargo " + cargoId + " compatible, wagon available at: " +
                    wagonAvailableTime + ", requesting locomotives (attempt " + request.attemptCount + ")");

            requestLocomotives(request);
        }

        private Date calculateWagonAvailableTime(Date requestedTime) {
            if (scheduleData.getSchedule().isEmpty()) {
                return requestedTime;
            }

            List<TimeSlot> schedule = scheduleData.getSchedule();
            TimeSlot lastSlot = schedule.get(schedule.size() - 1);

            if (requestedTime.after(lastSlot.getEndTime())) {
                return requestedTime;
            }

            return lastSlot.getEndTime();
        }
    }

    private void requestLocomotives(CargoRequest request) {
        try {
            request.lastAttemptTime = System.currentTimeMillis();

            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("locomotive");
            template.addServices(sd);
            DFAgentDescription[] locoAgents = DFService.search(this, template);

            if (locoAgents.length > 0) {
                locomotiveAgentsContacted.clear();
                locomotiveProposals.clear();

                for (DFAgentDescription desc : locoAgents) {
                    locomotiveAgentsContacted.add(desc.getName().getLocalName());
                    ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                    msg.addReceiver(desc.getName());

                    msg.setContent("WAGON_REQUEST:" + request.cargoId + ":" +
                            request.cargoType + ":" + request.weight + ":" +
                            request.fromStation + ":" + request.toStation + ":" +
                            wagon.getId() + ":" + wagon.getCapacity() + ":" +
                            request.wagonAvailableTime.getTime());

                    send(msg);
                }
                processedCargoRequests.add(request.cargoId + "_" + agentId);
                expectedLocomotiveResponses = locoAgents.length;
                startTime = System.currentTimeMillis();
                currentCargoId = request.cargoId;
                currentCargoToStation = request.toStation;

                System.out.println(agentId + ": Sent requests to " + locoAgents.length +
                        " locomotive agents for cargo " + request.cargoId);

            } else {
                System.out.println(agentId + ": No locomotive agents found!");
            }
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error searching for locomotive agents: " + e.getMessage());
        }
    }

    private class WaitForLocomotiveResponsesBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
        );
        private boolean isProcessing = false;

        public WaitForLocomotiveResponsesBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            if (isProcessing) {
                return;
            }

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String sender = msg.getSender().getLocalName();
                String content = msg.getContent();

                String cargoId = null;

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    // Формат предложения: время:locomotiveId:cargoId
                    String[] parts = content.split(":");
                    if (parts.length >= 3) {
                        cargoId = parts[2];
                    }
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    // Формат отказа: cargoId:reason
                    String[] parts = content.split(":");
                    if (parts.length >= 1) {
                        cargoId = parts[0];
                    }
                }

                if (cargoId == null) {
                    System.err.println(agentId + ": Cannot extract cargoId from message: " + content);
                    return;
                }

                // Проверяем, есть ли такой груз в pendingRequests
                if (pendingRequests.containsKey(cargoId)) {
                    CargoRequest request = pendingRequests.get(cargoId);

                    if (!request.isActive) {
                        return; // Запрос уже не активен
                    }

                    if (locomotiveProposals.containsKey(sender + "_" + cargoId)) {
                        return; // Уже обработали предложение от этого локомотива для этого груза
                    }

                    if (msg.getPerformative() == ACLMessage.PROPOSE) {
                        String[] parts = content.split(":");
                        Date availableTime = new Date(Long.parseLong(parts[0]));
                        String locomotiveId = parts[1];

                        Proposal proposal = new Proposal(sender, locomotiveId, availableTime, true);
                        locomotiveProposals.put(sender + "_" + cargoId, proposal);

                        System.out.println(agentId + ": Received proposal from locomotive " + sender +
                                " for cargo " + cargoId + " at time: " + availableTime);

                        // Проверяем, получили ли все ответы для этого груза
                        checkAllResponsesForCargo(cargoId, request);

                    } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                        String reason = content.substring(cargoId.length() + 1); // Получаем причину после cargoId:
                        Proposal proposal = new Proposal(sender, reason);
                        locomotiveProposals.put(sender + "_" + cargoId, proposal);

                        System.out.println(agentId + ": Received refusal from locomotive " + sender +
                                " for cargo " + cargoId + ": " + reason);

                        // Проверяем, получили ли все ответы для этого груза
                        checkAllResponsesForCargo(cargoId, request);
                    }
                } else {
                    System.out.println(agentId + ": Received message for unknown cargo " + cargoId +
                            ", current pending requests: " + pendingRequests.keySet());
                }
            }
        }

        private String extractCargoIdFromMessage(String content) {
            try {
                String[] parts = content.split(":");
                if (parts.length >= 3) {
                    // Формат предложения: время:locomotiveId:cargoId
                    return parts[2];
                } else if (parts.length == 2) {
                    // Формат отказа: cargoId:reason
                    return parts[0];
                } else if (parts.length == 1) {
                    // Просто сообщение без cargoId
                    return null;
                }
            } catch (Exception e) {
                System.err.println(agentId + ": Error extracting cargoId from: " + content);
            }
            return null;
        }

        private void checkAllResponsesForCargo(String cargoId, CargoRequest request) {
            // Считаем, сколько предложений/отказов получили для этого груза
            int responsesForCargo = 0;
            for (String key : locomotiveProposals.keySet()) {
                if (key.endsWith("_" + cargoId)) {
                    responsesForCargo++;
                }
            }

            // Если получили все ответы для этого груза
            if (responsesForCargo >= expectedLocomotiveResponses && expectedLocomotiveResponses > 0) {
                System.out.println(agentId + ": Received all " + responsesForCargo +
                        " responses for cargo " + cargoId);
                processProposalsForCargo(cargoId, request);
            }
        }

        private void processProposalsForCargo(String cargoId, CargoRequest request) {
            isProcessing = true;
            try {
                // Собираем все предложения для этого груза
                List<Proposal> proposalsForCargo = new ArrayList<>();
                for (Map.Entry<String, Proposal> entry : locomotiveProposals.entrySet()) {
                    if (entry.getKey().endsWith("_" + cargoId)) {
                        proposalsForCargo.add(entry.getValue());
                    }
                }

                System.out.println(agentId + ": Processing " + proposalsForCargo.size() +
                        " proposals for cargo " + cargoId);

                Proposal bestProposal = TimeUtils.selectBestProposal(proposalsForCargo);

                if (bestProposal != null && bestProposal.isAvailable()) {
                    System.out.println(agentId + ": Selected locomotive " + bestProposal.getResourceId() +
                            " with time: " + bestProposal.getAvailableTime() +
                            " for cargo " + cargoId);

                    // Сохраняем лучшее предложение для этого груза
                    request.bestProposal = bestProposal;

                    // Отправляем предложение грузу
                    ACLMessage reply = request.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(bestProposal.getAvailableTime().getTime() + ":" + wagon.getId());
                    myAgent.send(reply);

                    System.out.println(agentId + ": Sent proposal to cargo " + cargoId +
                            " for time: " + bestProposal.getAvailableTime());

                } else {
                    System.out.println(agentId + ": No suitable locomotive found for cargo " + cargoId);

                    if (request.canRetry()) {
                        System.out.println(agentId + ": Will retry request for cargo " + cargoId);
                        request.attemptCount++;
                        requestLocomotives(request);
                    } else {
                        System.out.println(agentId + ": Max retries reached for cargo " + cargoId);
                        sendRefusal(request.originalMessage, "NO_SUITABLE_LOCOMOTIVE", cargoId);

                        pendingRequests.remove(cargoId);
                        // Удаляем все предложения для этого груза
                        locomotiveProposals.keySet().removeIf(key -> key.endsWith("_" + cargoId));
                    }
                }
            } finally {
                isProcessing = false;
            }
        }
    }

    private class AcceptProposalBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                )
        );

        public AcceptProposalBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                System.out.println("DEBUG " + agentId + ": Received message: " + content + " from " + msg.getSender().getLocalName());

                if (content.startsWith("ACCEPT_PROPOSAL:")) {
                    String[] parts = content.substring("ACCEPT_PROPOSAL:".length()).split(":");
                    String cargoId = parts[0];
                    String toStation = parts[1];

                    // Ищем запрос для этого груза
                    if (pendingRequests.containsKey(cargoId)) {
                        CargoRequest request = pendingRequests.get(cargoId);

                        if (request.bestProposal != null) {
                            ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                            acceptMsg.addReceiver(new jade.core.AID(
                                    request.bestProposal.getAgentId(),
                                    jade.core.AID.ISLOCALNAME));
                            acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargoId + ":" + toStation);
                            myAgent.send(acceptMsg);

                            System.out.println("⏫ " + agentId + ": Sent ACCEPT_PROPOSAL to locomotive " +
                                    request.bestProposal.getAgentId() + " for cargo " + cargoId);

                            request.isActive = false;
                        }
                    }
                } else if (content.startsWith("SCHEDULE_FINALIZED:")) {
                    String[] parts = content.substring("SCHEDULE_FINALIZED:".length()).split(":");

                    if (parts.length >= 4) {
                        String scheduleId = parts[0];
                        Date departureTime = new Date(Long.parseLong(parts[1]));
                        Date arrivalTime = new Date(Long.parseLong(parts[2]));
                        String cargoId = parts[3];

                        System.out.println("✅ " + agentId + ": Received SCHEDULE_FINALIZED for cargo " + cargoId +
                                ", schedule: " + scheduleId + ", departure: " + departureTime + ", arrival: " + arrivalTime);

                        scheduleData.reserveTimeSlot(departureTime, arrivalTime);

                        System.out.println("✅ " + agentId + ": Schedule FINALIZED: " + scheduleId +
                                ", departure: " + departureTime + ", arrival: " + arrivalTime);

                        for (CargoRequest request : pendingRequests.values()) {
                            if (request.isActive) {
                                sendRefusal(request.originalMessage, "WAGON_MOVED_TO_NEW_STATION", request.cargoId);
                            }
                        }
                        pendingRequests.clear();
                        processedCargoRequests.clear();

                        if (currentCargoId != null && pendingRequests.containsKey(currentCargoId)) {
                            CargoRequest request = pendingRequests.get(currentCargoId);
                            if (request != null) {
                                wagon.setCurrentStation(request.toStation);
                                wagon.setAvailable(true);
                                System.out.println(agentId + ": Wagon moved to station: " + request.toStation);
                            }
                        } else if (currentCargoToStation != null) {
                            wagon.setCurrentStation(currentCargoToStation);
                            wagon.setAvailable(true);
                            System.out.println(agentId + ": Wagon moved to station: " + currentCargoToStation);
                        }

                        if (currentCargoId != null) {
                            pendingRequests.remove(currentCargoId);
                        }

                        resetResponseState();
                    } else {
                        System.err.println(agentId + ": Invalid SCHEDULE_FINALIZED format: " + content);
                    }
                } else if (content.startsWith("ROAD_REJECTED:")) {
                // Обработка отказа от дороги
                String[] parts = content.substring("ROAD_REJECTED:".length()).split(":");
                String reason = parts[0];
                String cargoId = parts.length > 1 ? parts[1] : "";
                String locomotiveId = parts.length > 2 ? parts[2] : "";

                System.out.println("❌ " + agentId + ": Road rejected schedule. Reason: " +
                        reason + ", cargo: " + cargoId + ", locomotive: " + locomotiveId);

                // Сбрасываем состояние для этого груза
                if (!cargoId.isEmpty() && pendingRequests.containsKey(cargoId)) {
                    pendingRequests.get(cargoId).isActive = false;
                    pendingRequests.remove(cargoId);
                }

                // Если это текущий груз, сбрасываем состояние
                if (currentCargoId != null && currentCargoId.equals(cargoId)) {
                    resetResponseState();
                }

                // Освобождаем вагон
                wagon.setAvailable(true);
                System.out.println(agentId + ": Wagon available again after road rejection");
            }
            }
        }
    }

    private class RetryManagerBehaviour extends TickerBehaviour {
        public RetryManagerBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            long currentTime = System.currentTimeMillis();
            List<String> toRemove = new ArrayList<>();

            for (Map.Entry<String, CargoRequest> entry : pendingRequests.entrySet()) {
                CargoRequest request = entry.getValue();

//                if (request.isExpired()) {
//                    System.out.println(agentId + ": Request for cargo " + request.cargoId + " expired");
//                    sendRefusal(request.originalMessage, "REQUEST_TIMEOUT", request.cargoId);
//                    toRemove.add(request.cargoId);
//                    processedCargoRequests.remove(request.cargoId + "_" + agentId);
//                    continue;
//                }

                if (request.isActive && request.canRetry()) {
                    if (!wagon.getCurrentStation().equals(request.fromStation)) {
                        System.out.println(agentId + ": Still not at requested station for cargo " +
                                request.cargoId + ". Current: " + wagon.getCurrentStation() +
                                ", Required: " + request.fromStation);
                        continue;
                    }

                    System.out.println(agentId + ": Retrying request for cargo " + request.cargoId +
                            " (attempt " + (request.attemptCount + 1) + ")");
                    request.attemptCount++;
                    requestLocomotives(request);
                }
            }

            for (String cargoId : toRemove) {
                pendingRequests.remove(cargoId);
            }

            boolean hasActiveRequests = false;
            for (CargoRequest request : pendingRequests.values()) {
                if (request.isActive) {
                    hasActiveRequests = true;
                    break;
                }
            }

            if (!hasActiveRequests && currentCargoId != null) {
                resetResponseState();
            }
        }
    }

    private void sendRefusal(ACLMessage originalMsg, String reason, String cargoId) {
        ACLMessage reply = originalMsg.createReply();
        reply.setPerformative(ACLMessage.REFUSE);
        reply.setContent(reason);
        send(reply);
    }

    private void resetResponseState() {
        locomotiveProposals.clear();
        locomotiveAgentsContacted.clear();
        expectedLocomotiveResponses = 0;
        bestLocomotiveProposal = null;
        currentCargoId = null;
        currentCargoToStation = null;
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error deregistering from DF: " + e.getMessage());
        }
        System.out.println(agentId + " terminated at station: " + wagon.getCurrentStation());
    }
}