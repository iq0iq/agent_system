package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import models.Locomotive;
import models.ScheduleData;
import models.Proposal;
import models.TimeSlot;
import utils.DataLoader;
import utils.TimeUtils;
import java.util.*;

public class LocomotiveAgent extends Agent {
    private Locomotive locomotive;
    private String agentId;
    private ScheduleData scheduleData;
    private Map<String, Proposal> roadProposals = new HashMap<>();
    private List<String> roadAgentsContacted = new ArrayList<>();
    private int expectedRoadResponses = 0;
    private Proposal bestRoadProposal = null;
    private long startTime;
    private long lastWagonRequestTime = 0;

    private Map<String, WagonRequest> pendingWagonRequests = new HashMap<>();
    private TrainComposition currentComposition = null;
    private boolean isProcessingComposition = false;
    private final long COMPOSITION_TIMEOUT = 15000;
    private final long WAGON_ACCEPT_TIMEOUT = 10000;

    private boolean isCollectingWagons = false;
    private long compositionStartTime = 0;

    private Map<String, WagonAcceptance> acceptedWagons = new HashMap<>();
    private boolean waitingForWagonAcceptances = false;
    private long wagonAcceptStartTime = 0;

    private Set<String> processedWagonRequests = new HashSet<>();
    private boolean roadRequestSent = false;

    private Set<String> processedCargoIdsInComposition = new HashSet<>();

    private class WagonRequest {
        String cargoId;
        String cargoType;
        double weight;
        String fromStation;
        String toStation;
        String wagonId;
        double wagonCapacity;
        Date wagonAvailableTime;
        ACLMessage originalMessage;
        long requestTime;
        boolean isProcessed;
        boolean isAccepted;

        WagonRequest(String cargoId, String cargoType, double weight,
                     String fromStation, String toStation, String wagonId,
                     double wagonCapacity, Date wagonAvailableTime,
                     ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.cargoType = cargoType;
            this.weight = weight;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.wagonId = wagonId;
            this.wagonCapacity = wagonCapacity;
            this.wagonAvailableTime = wagonAvailableTime;
            this.originalMessage = originalMessage;
            this.requestTime = System.currentTimeMillis();
            this.isProcessed = false;
            this.isAccepted = false;
        }
    }

    private class WagonAcceptance {
        String wagonId;
        String cargoId;
        String toStation;
        long acceptanceTime;

        WagonAcceptance(String wagonId, String cargoId, String toStation) {
            this.wagonId = wagonId;
            this.cargoId = cargoId;
            this.toStation = toStation;
            this.acceptanceTime = System.currentTimeMillis();
        }
    }

    private class TrainComposition {
        List<WagonRequest> wagons = new ArrayList<>();
        String fromStation;
        String toStation;
        double totalWeight = 0;
        Date earliestDepartureTime;
        String locomotiveId;
        boolean isConfirmed = false;
        String compositionId;

        Set<String> wagonIdsInComposition = new HashSet<>();
        Set<String> cargoIdsInComposition = new HashSet<>();

        TrainComposition(String fromStation, String toStation, String locomotiveId) {
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.locomotiveId = locomotiveId;
            this.earliestDepartureTime = new Date(0);
            this.compositionId = "COMP_" + System.currentTimeMillis() + "_" + locomotiveId;
        }

        boolean canAddWagon(WagonRequest request) {
            if (wagonIdsInComposition.contains(request.wagonId)) {
                return false;
            }
            if (cargoIdsInComposition.contains(request.cargoId)) {
                return false;
            }
            if (!request.fromStation.equals(fromStation) ||
                    !request.toStation.equals(toStation)) {
                return false;
            }
            if (totalWeight + request.weight > locomotive.getMaxWeightCapacity()) {
                return false;
            }
            return true;
        }

        void addWagon(WagonRequest request) {
            if (canAddWagon(request)) {
                wagons.add(request);
                wagonIdsInComposition.add(request.wagonId);
                cargoIdsInComposition.add(request.cargoId);
                totalWeight += request.weight;

                if (earliestDepartureTime.before(request.wagonAvailableTime)) {
                    earliestDepartureTime = request.wagonAvailableTime;
                }

                request.isProcessed = true;
                processedWagonRequests.add(request.wagonId + "_" + request.cargoId);

                System.out.println("Wagon " + request.wagonId + " added to composition " +
                        compositionId + " for cargo " + request.cargoId +
                        ". Total wagons: " + wagons.size() + ", total weight: " + totalWeight);
            } else {
            }
        }

        String getCargoIds() {
            StringBuilder sb = new StringBuilder();
            for (WagonRequest wagon : wagons) {
                if (sb.length() > 0) sb.append(",");
                sb.append(wagon.cargoId);
            }
            return sb.toString();
        }

        String getWagonIds() {
            StringBuilder sb = new StringBuilder();
            for (WagonRequest wagon : wagons) {
                if (sb.length() > 0) sb.append(",");
                sb.append(wagon.wagonId);
            }
            return sb.toString();
        }

        boolean containsCargo(String cargoId) {
            return cargoIdsInComposition.contains(cargoId);
        }

        WagonRequest getWagonByCargoId(String cargoId) {
            for (WagonRequest wagon : wagons) {
                if (wagon.cargoId.equals(cargoId)) {
                    return wagon;
                }
            }
            return null;
        }

        void markWagonAccepted(String wagonId) {
            for (WagonRequest wagon : wagons) {
                if (wagon.wagonId.equals(wagonId)) {
                    wagon.isAccepted = true;
                    break;
                }
            }
        }

        boolean allWagonsAccepted() {
            for (WagonRequest wagon : wagons) {
                if (!wagon.isAccepted) {
                    return false;
                }
            }
            return true;
        }
    }

    protected void setup() {
        agentId = (String) getArguments()[0];
        locomotive = DataLoader.getLocomotiveForAgent(agentId);

        if (locomotive == null) {
            System.out.println(agentId + ": No locomotive found!");
            doDelete();
            return;
        }

        scheduleData = new ScheduleData(locomotive.getId());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("locomotive");
        sd.setName("LocomotiveService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error registering with DF: " + e.getMessage());
        }

        addBehaviour(new WagonRequestBehaviour(this, 100));
        addBehaviour(new WaitForRoadResponsesBehaviour(this, 100));
        addBehaviour(new AcceptProposalBehaviour(this, 100));
        addBehaviour(new ScheduleFinalizedBehaviour(this, 100));
        addBehaviour(new RoadRejectionBehaviour(this, 100));
        addBehaviour(new CompositionTimerBehaviour(this, 2000));

        System.out.println(agentId + " started with locomotive: " + locomotive.getId() +
                " at station: " + locomotive.getCurrentStation());
    }

    private class WagonRequestBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);

        public WagonRequestBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("WAGON_REQUEST:")) {
                    handleWagonRequest(msg, content);
                }
            }
        }

        private void handleWagonRequest(ACLMessage msg, String content) {
            String[] parts = content.substring("WAGON_REQUEST:".length()).split(":");
            String cargoId = parts[0];
            String cargoType = parts[1];
            double weight = Double.parseDouble(parts[2]);
            String fromStation = parts[3];
            String toStation = parts[4];
            String wagonId = parts[5];
            double wagonCapacity = Double.parseDouble(parts[6]);
            Date wagonAvailableTime = new Date(Long.parseLong(parts[7]));

            String requestKey = wagonId + "_" + cargoId;

            if (currentComposition != null && currentComposition.containsCargo(cargoId)) {
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(new Date().getTime() + ":" + locomotive.getId() + ":" + cargoId);
                myAgent.send(reply);
                return;
            }
            WagonRequest request = new WagonRequest(cargoId, cargoType, weight, fromStation,
                    toStation, wagonId, wagonCapacity, wagonAvailableTime, msg);

            if (!locomotive.canPullWeight(weight)) {
                sendRefusal(msg, cargoId, "INSUFFICIENT_POWER");
                return;
            }
            if (!locomotive.getCurrentStation().equals(fromStation)) {
                sendRefusal(msg, cargoId, "NOT_AT_REQUESTED_STATION");
                return;
            }

            pendingWagonRequests.put(requestKey, request);
            lastWagonRequestTime = System.currentTimeMillis();
            if (!isCollectingWagons && !isProcessingComposition && !roadRequestSent) {
                isCollectingWagons = true;
                compositionStartTime = System.currentTimeMillis();
                System.out.println(agentId + ": Started wagon collection");
            }
        }

        private void sendRefusal(ACLMessage originalMsg, String cargoId, String reason) {
            ACLMessage reply = originalMsg.createReply();
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent(cargoId + ":" + reason);
            myAgent.send(reply);
        }
    }

    private void startCompositionFormation() {
        if (pendingWagonRequests.isEmpty()) {
            return;
        }

        isProcessingComposition = true;
        roadRequestSent = false;
        processedCargoIdsInComposition.clear();
        System.out.println(agentId + ": Starting composition formation process");

        currentComposition = null;
        List<WagonRequest> sortedRequests = new ArrayList<>(pendingWagonRequests.values());
        sortedRequests.sort(Comparator.comparing(r -> r.wagonAvailableTime));

        for (WagonRequest request : sortedRequests) {
            if (currentComposition == null) {
                currentComposition = new TrainComposition(
                        request.fromStation,
                        request.toStation,
                        locomotive.getId()
                );
                currentComposition.addWagon(request);
                processedCargoIdsInComposition.add(request.cargoId);
            } else if (currentComposition.canAddWagon(request)) {
                currentComposition.addWagon(request);
                processedCargoIdsInComposition.add(request.cargoId);
            } else {
                sendRefusal(request.originalMessage, request.cargoId, "CANNOT_ADD_TO_COMPOSITION");
            }
        }
        pendingWagonRequests.clear();

        if (currentComposition != null && currentComposition.wagons.size() > 0) {
            System.out.println(agentId + ": Formed composition " + currentComposition.compositionId +
                    " with " + currentComposition.wagons.size() +
                    " unique wagons, " + currentComposition.cargoIdsInComposition.size() +
                    " unique cargoes");
            requestRoadsForComposition();
        } else {
            System.out.println(agentId + ": No composition could be formed");
            resetCompositionState();
        }
    }

    private void requestRoadsForComposition() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("road");
            template.addServices(sd);
            DFAgentDescription[] roadAgents = DFService.search(this, template);

            if (roadAgents.length > 0) {
                roadAgentsContacted.clear();
                roadProposals.clear();
                roadRequestSent = true;

                Date locomotiveAvailableTime = calculateLocomotiveAvailableTime(
                        currentComposition.earliestDepartureTime
                );

                Date trainAvailableTime = locomotiveAvailableTime.after(
                        currentComposition.earliestDepartureTime
                ) ? locomotiveAvailableTime : currentComposition.earliestDepartureTime;

                for (DFAgentDescription desc : roadAgents) {
                    roadAgentsContacted.add(desc.getName().getLocalName());
                    ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                    msg.addReceiver(desc.getName());
                    msg.setContent("LOCOMOTIVE_REQUEST:" +
                            currentComposition.getCargoIds() + ":" +
                            currentComposition.fromStation + ":" +
                            currentComposition.toStation + ":" +
                            currentComposition.totalWeight + ":" +
                            locomotive.getId() + ":" +
                            currentComposition.getWagonIds() + ":" +
                            trainAvailableTime.getTime() + ":" +
                            locomotive.getSpeed());
                    send(msg);
                    System.out.println(agentId + ": Sent composition request to road: " +
                            desc.getName().getLocalName() +
                            " with departure time: " + trainAvailableTime +
                            ", cargoes: " + currentComposition.getCargoIds() +
                            ", wagons: " + currentComposition.getWagonIds() +
                            ", locomotive speed: " + locomotive.getSpeed() + " km/h");
                }
                expectedRoadResponses = roadAgents.length;
                startTime = System.currentTimeMillis();
                System.out.println(agentId + ": Sent requests to " + roadAgents.length + " road agents");
            } else {
                System.out.println(agentId + ": No road agents found!");
                sendRefusalsToWagons("NO_ROAD_AVAILABLE");
                resetCompositionState();
            }
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error searching for road agents: " + e.getMessage());
            e.printStackTrace();
            resetCompositionState();
        }
    }


    private Date calculateLocomotiveAvailableTime(Date requestedTime) {
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

    private class WaitForRoadResponsesBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
        );
        private boolean isProcessing = false;

        public WaitForRoadResponsesBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            if (isProcessing) {
                return;
            }
            if (!isProcessingComposition || currentComposition == null || !roadRequestSent) {
                return;
            }
            if (roadProposals.size() >= expectedRoadResponses && expectedRoadResponses > 0) {
                System.out.println(agentId + ": Received all " + roadProposals.size() + " road responses");
                processRoadProposals();
                return;
            }
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null && currentComposition != null) {
                String sender = msg.getSender().getLocalName();

                if (roadProposals.containsKey(sender)) {
                    return;
                }
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    String content = msg.getContent();
                    String[] parts = content.split(":");
                    Date availableTime = new Date(Long.parseLong(parts[0]));
                    String routeId = parts[1];
                    String cargoIds = parts.length > 2 ? parts[2] : "";

                    Proposal proposal = new Proposal(sender, routeId, availableTime, true);
                    roadProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from road " + sender +
                            " with time: " + availableTime + " for cargoes: " + cargoIds);
                    if (roadProposals.size() >= expectedRoadResponses && expectedRoadResponses > 0) {
                        System.out.println(agentId + ": Received all " + roadProposals.size() + " road responses");
                        processRoadProposals();
                    }
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    roadProposals.put(sender, proposal);
                    if (roadProposals.size() >= expectedRoadResponses && expectedRoadResponses > 0) {
                        System.out.println(agentId + ": Received all " + roadProposals.size() + " road responses");
                        processRoadProposals();
                    }
                }
            }
        }

        private void processRoadProposals() {
            if (isProcessing) {
                return;
            }

            isProcessing = true;
            try {
                List<Proposal> allProposals = new ArrayList<>(roadProposals.values());
                bestRoadProposal = TimeUtils.selectBestProposal(allProposals);

                if (bestRoadProposal != null && bestRoadProposal.isAvailable()) {
                    System.out.println(agentId + ": Selected road " + bestRoadProposal.getResourceId() +
                            " with time: " + bestRoadProposal.getAvailableTime());
                    for (WagonRequest wagon : currentComposition.wagons) {
                        String key = wagon.wagonId + "_" + wagon.cargoId;
                        pendingWagonRequests.remove(key);
                        ACLMessage reply = wagon.originalMessage.createReply();
                        reply.setPerformative(ACLMessage.PROPOSE);
                        reply.setContent(bestRoadProposal.getAvailableTime().getTime() + ":" +
                                locomotive.getId() + ":" + wagon.cargoId);
                        myAgent.send(reply);
                        System.out.println(agentId + ": Sent proposal to wagon " + wagon.wagonId +
                                " for cargo " + wagon.cargoId +
                                " at time: " + bestRoadProposal.getAvailableTime());
                    }

                    roadProposals.clear();
                    roadAgentsContacted.clear();
                    expectedRoadResponses = 0;
                } else {
                    System.out.println(agentId + ": No suitable road found!");
                    sendRefusalsToWagons("NO_SUITABLE_ROAD");
                    resetCompositionState();
                }
            } finally {
                isProcessing = false;
            }
        }
    }

    private class AcceptProposalBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);

        public AcceptProposalBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("ACCEPT_PROPOSAL:")) {
                    String[] parts = content.substring("ACCEPT_PROPOSAL:".length()).split(":");
                    String cargoId = parts[0];
                    String toStation = parts[1];

                    WagonRequest wagonRequest = null;
                    if (currentComposition != null) {
                        wagonRequest = currentComposition.getWagonByCargoId(cargoId);
                    }

                    if (wagonRequest != null) {
                        acceptedWagons.put(wagonRequest.wagonId, new WagonAcceptance(wagonRequest.wagonId, cargoId, toStation));
                        currentComposition.markWagonAccepted(wagonRequest.wagonId);

                        String key = wagonRequest.wagonId + "_" + wagonRequest.cargoId;
                        pendingWagonRequests.remove(key);

                        System.out.println(agentId + ": Wagon " + wagonRequest.wagonId +
                                " accepted for cargo " + cargoId +
                                ". Accepted wagons: " + acceptedWagons.size() +
                                "/" + (currentComposition != null ? currentComposition.wagons.size() : 0));

                        if (!waitingForWagonAcceptances && acceptedWagons.size() == 1) {
                            waitingForWagonAcceptances = true;
                            wagonAcceptStartTime = System.currentTimeMillis();
                            System.out.println(agentId + ": Waiting for other wagons to accept (timeout: " +
                                    WAGON_ACCEPT_TIMEOUT + "ms)");
                        }

                        checkAndSendRoadAcceptance();
                    } else {
                        System.out.println(agentId + ": Received ACCEPT_PROPOSAL for cargo " +
                                cargoId + " but no matching wagon in current composition");
                    }
                }
            }
        }

        private void checkAndSendRoadAcceptance() {
            if (currentComposition == null) {
                return;
            }
            if (!waitingForWagonAcceptances) return;

            long currentTime = System.currentTimeMillis();
            boolean shouldSend = false;
            if (currentTime - wagonAcceptStartTime > WAGON_ACCEPT_TIMEOUT) {
                System.out.println(agentId + ": Wagon acceptance timeout reached");
                shouldSend = true;
            }
            if (currentComposition.allWagonsAccepted()) {
                System.out.println(agentId + ": All wagons have accepted");
                shouldSend = true;
            }
            if (shouldSend) {
                sendRoadAcceptance();
                waitingForWagonAcceptances = false;
            }
        }

        private void sendRoadAcceptance() {
            if (bestRoadProposal == null || currentComposition == null || acceptedWagons.isEmpty()) {
                System.out.println(agentId + ": Cannot send road acceptance - missing required data");
                Set<String> allCargoIds = new HashSet<>();
                Set<String> allWagonIds = new HashSet<>();
                for (WagonAcceptance acceptance : acceptedWagons.values()) {
                    allCargoIds.add(acceptance.cargoId);
                    allWagonIds.add(acceptance.wagonId);
                }
                for (String cargoId : allCargoIds) {
                    try {
                        String cargoAgentName = "CargoAgent" + cargoId.substring(1);
                        ACLMessage cargoRejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        cargoRejectMsg.addReceiver(new jade.core.AID(cargoAgentName, jade.core.AID.ISLOCALNAME));
                        cargoRejectMsg.setContent("LOCOMOTIVE_REJECTED:" + "unknown" + ":" + agentId);
                        send(cargoRejectMsg);
                    } catch (Exception e) {
                        System.err.println("   Error sending rejection to cargo " + cargoId + ": " + e.getMessage());
                    }
                }
                for (String wagonId : allWagonIds) {
                    try {
                        String wagonAgentName = "WagonAgent" + wagonId.substring(1);
                        ACLMessage wagonRejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        wagonRejectMsg.addReceiver(new jade.core.AID(wagonAgentName, jade.core.AID.ISLOCALNAME));
                        wagonRejectMsg.setContent("LOCOMOTIVE_REJECTED:" + "unknown" + ":" + agentId);
                        send(wagonRejectMsg);
                    } catch (Exception e) {
                        System.err.println("   Error sending rejection to wagon " + wagonId + ": " + e.getMessage());
                    }
                }
                resetCompositionState();
                return;
            }

            StringBuilder acceptedWagonIds = new StringBuilder();
            StringBuilder acceptedCargoIds = new StringBuilder();

            for (WagonAcceptance acceptance : acceptedWagons.values()) {
                if (acceptedWagonIds.length() > 0) {
                    acceptedWagonIds.append(",");
                    acceptedCargoIds.append(",");
                }
                acceptedWagonIds.append(acceptance.wagonId);
                acceptedCargoIds.append(acceptance.cargoId);
            }

            String acceptContent = "ACCEPT_PROPOSAL:" +
                    acceptedCargoIds.toString() + ":" +
                    currentComposition.fromStation + ":" +
                    currentComposition.toStation + ":" +
                    currentComposition.totalWeight + ":" +
                    currentComposition.locomotiveId + ":" +
                    acceptedWagonIds.toString() + ":" +
                    currentComposition.earliestDepartureTime.getTime() + ":" +
                    locomotive.getSpeed() + ":" +
                    bestRoadProposal.getAvailableTime().getTime();

            ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            acceptMsg.addReceiver(new jade.core.AID(bestRoadProposal.getAgentId(),
                    jade.core.AID.ISLOCALNAME));
            acceptMsg.setContent(acceptContent);
            myAgent.send(acceptMsg);

            System.out.println(agentId + ": Sent ACCEPT_PROPOSAL to road " +
                    bestRoadProposal.getAgentId() + " with all necessary data");

            currentComposition.isConfirmed = true;
        }
    }

    private class ScheduleFinalizedBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);

        public ScheduleFinalizedBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_FINALIZED:")) {
                    String[] parts = content.substring("SCHEDULE_FINALIZED:".length()).split(":");
                    String scheduleId = parts[0];
                    Date departureTime = new Date(Long.parseLong(parts[1]));
                    Date arrivalTime = new Date(Long.parseLong(parts[2]));

                    scheduleData.reserveTimeSlot(departureTime, arrivalTime);
                    for (WagonRequest request : pendingWagonRequests.values()) {
                        sendRefusal(request.originalMessage, request.cargoId, "LOCOMOTIVE_MOVED_TO_NEW_STATION");
                    }
                    pendingWagonRequests.clear();
                    processedWagonRequests.clear();

                    System.out.println(agentId + ": Schedule FINALIZED: " + scheduleId +
                            ", departure: " + departureTime + ", arrival: " + arrivalTime);

                    if (currentComposition != null) {
                        locomotive.setCurrentStation(currentComposition.toStation);
                    }
                    locomotive.setAvailable(true);

                    System.out.println(agentId + ": Locomotive moved to station: " +
                            locomotive.getCurrentStation());

                    resetCompositionState();
                }
            }
        }
    }

    private class CompositionTimerBehaviour extends TickerBehaviour {
        public CompositionTimerBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            long currentTime = System.currentTimeMillis();
            if (isCollectingWagons && !isProcessingComposition && !roadRequestSent) {
                if ((currentTime - compositionStartTime) > COMPOSITION_TIMEOUT) {
                    System.out.println(agentId + ": Wagon collection timeout reached, forming composition");
                    startCompositionFormation();
                    isCollectingWagons = false;
                }
            }
        }
    }

    private class RoadRejectionBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL);

        public RoadRejectionBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                System.out.println(agentId + ": Received REJECT_PROPOSAL from road: " + content);
                if (content.startsWith("TIME_SLOT_UNAVAILABLE")) {
                    System.out.println(agentId + ": Road rejected our proposal. Reason: " + content);
                    resetCompositionState();

                }
            }
        }
    }

    private void sendRefusalsToWagons(String reason) {
        for (WagonRequest request : pendingWagonRequests.values()) {
            ACLMessage reply = request.originalMessage.createReply();
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent(request.cargoId + ":" + reason);
            send(reply);
        }
    }

    private void resetCompositionState() {
        if (currentComposition != null) {
            for (WagonRequest wagon : currentComposition.wagons) {
                String key = wagon.wagonId + "_" + wagon.cargoId;
                pendingWagonRequests.remove(key);
                processedWagonRequests.remove(key);
                if (!wagon.isAccepted && wagon.originalMessage != null) {
                    sendRefusal(wagon.originalMessage, wagon.cargoId, "COMPOSITION_CANCELLED");
                }
            }
        }
        roadProposals.clear();
        roadAgentsContacted.clear();
        expectedRoadResponses = 0;
        bestRoadProposal = null;
        currentComposition = null;
        isProcessingComposition = false;
        roadRequestSent = false;
        acceptedWagons.clear();
        waitingForWagonAcceptances = false;
        wagonAcceptStartTime = 0;
        processedCargoIdsInComposition.clear();
        isCollectingWagons = false;
        System.out.println(agentId + ": Composition state reset");
    }

    private void sendRefusal(ACLMessage originalMsg, String cargoId, String reason) {
        ACLMessage reply = originalMsg.createReply();
        reply.setPerformative(ACLMessage.REFUSE);
        reply.setContent(cargoId + ":" + reason);
        send(reply);
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error deregistering from DF: " + e.getMessage());
        }
        System.out.println(agentId + " terminated at station: " + locomotive.getCurrentStation());
    }
}