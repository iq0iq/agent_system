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
    private final long REQUEST_TIMEOUT = 15000;

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

            if (currentCargoId == null || !pendingRequests.containsKey(currentCargoId)) {
                return;
            }

            CargoRequest request = pendingRequests.get(currentCargoId);
            if (request == null || !request.isActive) {
                return;
            }

            // УДАЛЕНО: Таймаут ожидания ответов
            // if ((System.currentTimeMillis() - startTime) > REQUEST_TIMEOUT) {
            //     System.out.println(agentId + ": Timeout waiting for locomotive responses for cargo " + currentCargoId);
            //
            //     if (locomotiveProposals.size() > 0) {
            //         processLocomotiveProposals(request);
            //     } else {
            //         if (request.canRetry()) {
            //             System.out.println(agentId + ": Will retry request for cargo " + currentCargoId);
            //             request.attemptCount++;
            //             requestLocomotives(request);
            //         } else {
            //             System.out.println(agentId + ": Max retries reached or request expired for cargo " + currentCargoId);
            //             sendRefusal(request.originalMessage, "NO_LOCOMOTIVE_RESPONSE", currentCargoId);
            //             request.isActive = false;
            //         }
            //     }
            //     return;
            // }

            if (locomotiveProposals.size() >= expectedLocomotiveResponses && expectedLocomotiveResponses > 0) {
                System.out.println(agentId + ": Received all " + locomotiveProposals.size() + " locomotive responses");
                processLocomotiveProposals(request);
                return;
            }

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentCargoId != null) {
                String sender = msg.getSender().getLocalName();

                if (locomotiveProposals.containsKey(sender)) {
                    return;
                }

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    String content = msg.getContent();
                    String[] parts = content.split(":");
                    Date availableTime = new Date(Long.parseLong(parts[0]));
                    String locomotiveId = parts[1];
                    String cargoId = parts.length > 2 ? parts[2] : null;

                    if (currentCargoId.equals(cargoId)) {
                        Proposal proposal = new Proposal(sender, locomotiveId, availableTime, true);
                        locomotiveProposals.put(sender, proposal);
                        System.out.println(agentId + ": Received proposal from locomotive " + sender +
                                " for cargo " + cargoId + " at time: " + availableTime);

                        if (locomotiveProposals.size() >= expectedLocomotiveResponses && expectedLocomotiveResponses > 0) {
                            System.out.println(agentId + ": Received all " + locomotiveProposals.size() + " locomotive responses");
                            processLocomotiveProposals(request);
                        }
                    }
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    String reason = msg.getContent();

                    String cargoId = currentCargoId;
                    if (reason.contains(":")) {
                        String[] parts = reason.split(":");
                        cargoId = parts[0];
                    }

                    if (currentCargoId.equals(cargoId)) {
                        Proposal proposal = new Proposal(sender, reason);
                        locomotiveProposals.put(sender, proposal);
                        System.out.println(agentId + ": Received refusal from locomotive " + sender +
                                " for cargo " + cargoId + ": " + reason);

                        if (locomotiveProposals.size() >= expectedLocomotiveResponses && expectedLocomotiveResponses > 0) {
                            System.out.println(agentId + ": Received all " + locomotiveProposals.size() + " locomotive responses");
                            processLocomotiveProposals(request);
                        }
                    }
                }
            }
        }

        private void processLocomotiveProposals(CargoRequest request) {
            if (isProcessing) {
                return;
            }

            isProcessing = true;
            try {
                System.out.println(agentId + ": Processing " + locomotiveProposals.size() + " proposals for cargo " + request.cargoId);

                List<Proposal> allProposals = new ArrayList<>(locomotiveProposals.values());
                bestLocomotiveProposal = TimeUtils.selectBestProposal(allProposals);

                if (bestLocomotiveProposal != null && bestLocomotiveProposal.isAvailable()) {
                    System.out.println(agentId + ": Selected locomotive " + bestLocomotiveProposal.getResourceId() +
                            " with time: " + bestLocomotiveProposal.getAvailableTime() +
                            " for cargo " + request.cargoId);

                    ACLMessage reply = request.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(bestLocomotiveProposal.getAvailableTime().getTime() + ":" + wagon.getId());
                    myAgent.send(reply);

                    System.out.println(agentId + ": Sent proposal to cargo " + request.cargoId +
                            " for time: " + bestLocomotiveProposal.getAvailableTime());

                    currentCargoToStation = request.toStation;

                    locomotiveProposals.clear();
                    locomotiveAgentsContacted.clear();
                    expectedLocomotiveResponses = 0;
                } else {
                    System.out.println(agentId + ": No suitable locomotive found for cargo " + request.cargoId);

                    if (request.canRetry()) {
                        System.out.println(agentId + ": Will retry request for cargo " + request.cargoId);
                        request.attemptCount++;

                        locomotiveProposals.clear();
                        locomotiveAgentsContacted.clear();
                        expectedLocomotiveResponses = 0;
                        bestLocomotiveProposal = null;

                        requestLocomotives(request);
                    } else {
                        System.out.println(agentId + ": Max retries reached for cargo " + request.cargoId);
                        sendRefusal(request.originalMessage, "NO_SUITABLE_LOCOMOTIVE", request.cargoId);

                        pendingRequests.remove(request.cargoId);
                        resetResponseState();
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
                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
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

                    if (bestLocomotiveProposal != null && pendingRequests.containsKey(cargoId)) {
                        ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                        acceptMsg.addReceiver(new jade.core.AID(bestLocomotiveProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                        acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargoId + ":" + toStation);
                        myAgent.send(acceptMsg);

                        System.out.println("⏫ " + agentId + ": Sent ACCEPT_PROPOSAL to locomotive " +
                                bestLocomotiveProposal.getAgentId() + " for cargo " + cargoId);

                        if (pendingRequests.containsKey(cargoId)) {
                            pendingRequests.get(cargoId).isActive = false;
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

                if (request.isExpired()) {
                    System.out.println(agentId + ": Request for cargo " + request.cargoId + " expired");
                    sendRefusal(request.originalMessage, "REQUEST_TIMEOUT", request.cargoId);
                    toRemove.add(request.cargoId);
                    processedCargoRequests.remove(request.cargoId + "_" + agentId);
                    continue;
                }

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