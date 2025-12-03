package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
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
        }
    }

    private WagonRequest currentRequest = null;
    private double currentTrainWeight = 0;
    private List<String> currentWagons = new ArrayList<>();
    private Date calculatedDepartureTime; // Рассчитанное время отправления

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
        } catch (FIPAException e) {}

        addBehaviour(new WagonRequestBehaviour());
//        addBehaviour(new WaitForRoadResponsesBehaviour());
//        addBehaviour(new AcceptProposalBehaviour());
//        addBehaviour(new RejectProposalBehaviour());
        System.out.println(agentId + " started with locomotive: " + locomotive.getId() +
                " at station: " + locomotive.getCurrentStation() +
                ", schedule size: " + scheduleData.getSchedule().size());
    }

    private class WagonRequestBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("WAGON_REQUEST:")) {
                    handleWagonRequest(msg, content);
                }
            } else {
                block();
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

            // Рассчитываем свободное время локомотива
            Date locomotiveAvailableTime = calculateLocomotiveAvailableTime(wagonAvailableTime);

            // Время отправления будет максимальным из времен готовности локомотива и вагона
            calculatedDepartureTime = locomotiveAvailableTime.after(wagonAvailableTime) ?
                    locomotiveAvailableTime : wagonAvailableTime;

            if (!locomotive.canPullWeight(weight)) {
                System.out.println(agentId + ": Cannot pull weight " + weight);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("INSUFFICIENT_POWER");
                myAgent.send(reply);
                return;
            }

            if (!locomotive.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Not at requested station");
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NOT_AT_REQUESTED_STATION");
                myAgent.send(reply);
                return;
            }

            if (currentTrainWeight + weight > locomotive.getMaxWeightCapacity()) {
                System.out.println(agentId + ": Adding wagon would exceed capacity");
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("EXCEEDS_CAPACITY");
                myAgent.send(reply);
                return;
            }

            currentRequest = new WagonRequest(cargoId, cargoType, weight, fromStation,
                    toStation, wagonId, wagonCapacity, wagonAvailableTime, msg);

            currentTrainWeight += weight;
            currentWagons.add(wagonId);

            System.out.println(agentId + ": Added wagon " + wagonId + " to train. " +
                    "Current weight: " + currentTrainWeight + ", wagons: " +
                    currentWagons.size() + ", calculated departure time: " + calculatedDepartureTime +
                    " (locomotive available: " + locomotiveAvailableTime +
                    ", wagon available: " + wagonAvailableTime + ")");

            requestRoads();
        }

        private Date calculateLocomotiveAvailableTime(Date requestedTime) {
            // Если расписание пустое, локомотив свободен с requestedTime
            if (scheduleData.getSchedule().isEmpty()) {
                return requestedTime;
            }

            // Получаем последний занятый временной слот
            List<TimeSlot> schedule = scheduleData.getSchedule();
            TimeSlot lastSlot = schedule.get(schedule.size() - 1);

            // Если requestedTime позже, чем время окончания последнего слота,
            // то локомотив свободен с requestedTime
            if (requestedTime.after(lastSlot.getEndTime())) {
                return requestedTime;
            }

            // Иначе локомотив будет свободен после окончания последнего слота
            return lastSlot.getEndTime();
        }

        private void requestRoads() {
            if (currentRequest == null) {
                return;
            }

            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("road");
                template.addServices(sd);
                DFAgentDescription[] roadAgents = DFService.search(myAgent, template);

                if (roadAgents.length > 0) {
                    roadAgentsContacted.clear();
                    roadProposals.clear();

                    for (DFAgentDescription desc : roadAgents) {
                        roadAgentsContacted.add(desc.getName().getLocalName());
                        ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                        msg.addReceiver(desc.getName());
                        msg.setContent("LOCOMOTIVE_REQUEST:" + currentRequest.cargoId + ":" +
                                currentRequest.fromStation + ":" + currentRequest.toStation + ":" +
                                currentTrainWeight + ":" + locomotive.getId() + ":" +
                                getWagonIds() + ":" + calculatedDepartureTime.getTime()); // Отправляем рассчитанное время отправления
                        myAgent.send(msg);
                        System.out.println(agentId + ": Sent request to road: " +
                                desc.getName().getLocalName() +
                                " with departure time: " + calculatedDepartureTime);
                    }
                    expectedRoadResponses = roadAgents.length;
                    startTime = System.currentTimeMillis();
                    System.out.println(agentId + ": Sent requests to " + roadAgents.length + " road agents");
                } else {
                    System.out.println(agentId + ": No road agents found!");
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_ROAD_AVAILABLE");
                    myAgent.send(reply);
                    resetState();
                }
            } catch (FIPAException e) {
                e.printStackTrace();
                resetState();
            }
        }

        private String getWagonIds() {
            return String.join(",", currentWagons);
        }
    }

    private class WaitForRoadResponsesBehaviour extends CyclicBehaviour {
        private final long TIMEOUT = 30000; // 30 секунд

        public void action() {
            if (currentRequest == null) {
                block(1000);
                return;
            }

            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
                System.out.println(agentId + ": Timeout waiting for road responses. Received " +
                        roadProposals.size() + " of " + expectedRoadResponses);

                if (roadProposals.size() > 0) {
                    processRoadProposals();
                } else {
                    System.out.println(agentId + ": No road proposals received");
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_ROAD_RESPONSE");
                    myAgent.send(reply);
                    resetState();
                }
                return;
            }

            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
            );

            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest != null) {
                String sender = msg.getSender().getLocalName();

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    String content = msg.getContent();
                    String[] parts = content.split(":");
                    Date availableTime = new Date(Long.parseLong(parts[0]));
                    String routeId = parts[1];

                    Proposal proposal = new Proposal(sender, routeId, availableTime, true);
                    roadProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from road " + sender +
                            " with time: " + availableTime);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    roadProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from road " + sender);
                }

                if (roadProposals.size() >= expectedRoadResponses) {
                    System.out.println(agentId + ": Received all " + roadProposals.size() + " road responses");
                    processRoadProposals();
                }
            } else {
                block(1000);
            }
        }

        private void processRoadProposals() {
            List<Proposal> allProposals = new ArrayList<>(roadProposals.values());
            bestRoadProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestRoadProposal != null) {
                System.out.println(agentId + ": Selected road " + bestRoadProposal.getResourceId() +
                        " with time: " + bestRoadProposal.getAvailableTime());

                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(bestRoadProposal.getAvailableTime().getTime() + ":" +
                        locomotive.getId() + ":" + currentRequest.cargoId);
                myAgent.send(reply);
                System.out.println(agentId + ": Sent proposal to wagon " + currentRequest.wagonId +
                        " for time: " + bestRoadProposal.getAvailableTime());
            } else {
                System.out.println(agentId + ": No suitable road found!");
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_SUITABLE_ROAD");
                myAgent.send(reply);
                resetState();
            }
        }
    }

    private class AcceptProposalBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("ACCEPT_PROPOSAL:")) {
                    String[] parts = content.substring("ACCEPT_PROPOSAL:".length()).split(":");
                    String cargoId = parts[0];
                    String toStation = parts[1];
                    String departureTimeStr = parts.length > 2 ? parts[2] : null;

                    if (bestRoadProposal != null) {
                        // Бронируем выбранную дорогу
                        ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                        acceptMsg.addReceiver(new jade.core.AID(bestRoadProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                        acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargoId + ":" + toStation);

                        myAgent.send(acceptMsg);

                        System.out.println(agentId + ": Accepted road " + bestRoadProposal.getAgentId() +
                                " for cargo " + cargoId);
                    }
                } else if (content.startsWith("SCHEDULE_CREATED:")) {
                    String[] parts = content.substring("SCHEDULE_CREATED:".length()).split(":");
                    String scheduleId = parts[0];
                    Date departureTime = new Date(Long.parseLong(parts[1]));
                    Date arrivalTime = new Date(Long.parseLong(parts[2]));

                    // Резервируем время для локомотива
                    scheduleData.reserveTimeSlot(departureTime, arrivalTime);

                    System.out.println(agentId + ": Reserved time slot in locomotive schedule: " +
                            departureTime + " - " + arrivalTime);

                    // Пересылаем подтверждение вагону
                    ACLMessage forwardMsg = new ACLMessage(ACLMessage.INFORM);
                    forwardMsg.addReceiver(currentRequest.originalMessage.getSender());
                    forwardMsg.setContent("SCHEDULE_CREATED:" + scheduleId + ":" +
                            departureTime.getTime() + ":" + arrivalTime.getTime());
                    myAgent.send(forwardMsg);

                    System.out.println(agentId + ": Schedule created: " + scheduleId);

                    // После доставки обновляем состояние локомотива
                    locomotive.setCurrentStation(currentRequest.toStation);
                    locomotive.setAvailable(true);
                    System.out.println(agentId + ": Locomotive moved to station: " +
                            currentRequest.toStation + ", arrival time: " + arrivalTime);

                    resetState();
                }
            } else {
                block();
            }
        }
    }

    private class RejectProposalBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("REJECT_PROPOSAL:")) {
                    String cargoId = content.substring("REJECT_PROPOSAL:".length());

                    if (bestRoadProposal != null) {
                        // Отменяем бронирование дороги
                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        rejectMsg.addReceiver(new jade.core.AID(bestRoadProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                        rejectMsg.setContent("REJECT_PROPOSAL:" + cargoId);
                        myAgent.send(rejectMsg);

                        System.out.println(agentId + ": Rejected by wagon, canceling road");
                    }

                    resetState();
                }
            } else {
                block();
            }
        }
    }

    private void resetState() {
        roadProposals.clear();
        roadAgentsContacted.clear();
        expectedRoadResponses = 0;
        bestRoadProposal = null;
        currentRequest = null;
        currentTrainWeight = 0;
        currentWagons.clear();
        calculatedDepartureTime = null;
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated at station: " + locomotive.getCurrentStation());
    }
}