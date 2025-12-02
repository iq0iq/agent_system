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
        String priority;
        ACLMessage originalMessage;

        WagonRequest(String cargoId, String cargoType, double weight,
                     String fromStation, String toStation, String wagonId,
                     double wagonCapacity, String priority, ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.cargoType = cargoType;
            this.weight = weight;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.wagonId = wagonId;
            this.wagonCapacity = wagonCapacity;
            this.priority = priority;
            this.originalMessage = originalMessage;
        }
    }

    private WagonRequest currentRequest = null;

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
        addBehaviour(new WaitForRoadResponsesBehaviour());
        addBehaviour(new BookingConfirmationBehaviour());
        addBehaviour(new ScheduleFinalizationBehaviour());
        System.out.println(agentId + " started with locomotive: " + locomotive.getId());
    }

    private class WagonRequestBehaviour extends CyclicBehaviour {
        public void action() {
            // Ждем запросы от вагонов
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest == null) {
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
            String priority = parts[7];

            currentRequest = new WagonRequest(cargoId, cargoType, weight, fromStation,
                    toStation, wagonId, wagonCapacity, priority, msg);

            // Проверяем возможность перевозки
            if (!locomotive.canPullWeight(weight)) {
                System.out.println(agentId + ": Cannot pull weight " + weight +
                        ", max capacity: " + locomotive.getMaxWeightCapacity());
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("INSUFFICIENT_POWER");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            if (!locomotive.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Not at requested station. Current: " +
                        locomotive.getCurrentStation() + ", Requested: " + fromStation);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NOT_AT_REQUESTED_STATION");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            // Если проверки прошли, запрашиваем дороги
            System.out.println(agentId + ": Can pull weight " + weight + ", requesting roads");
            requestRoads();
        }

        private void requestRoads() {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("road");
                template.addServices(sd);
                DFAgentDescription[] roadAgents = DFService.search(myAgent, template);

                if (roadAgents.length > 0) {
                    for (DFAgentDescription desc : roadAgents) {
                        roadAgentsContacted.add(desc.getName().getLocalName());
                        ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                        msg.addReceiver(desc.getName());
                        msg.setContent("LOCOMOTIVE_REQUEST:" + currentRequest.cargoId + ":" +
                                currentRequest.fromStation + ":" + currentRequest.toStation + ":" +
                                currentRequest.weight + ":" + locomotive.getId() + ":" +
                                currentRequest.wagonId + ":" + currentRequest.priority);
                        myAgent.send(msg);
                        System.out.println(agentId + ": Sent request to road: " + desc.getName().getLocalName());
                    }
                    expectedRoadResponses = roadAgents.length;
                    startTime = System.currentTimeMillis();
                    System.out.println(agentId + ": Sent requests to " + roadAgents.length + " road agents");
                } else {
                    System.out.println(agentId + ": No road agents found!");
                    // Отправляем отказ вагону
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_ROAD_AVAILABLE");
                    myAgent.send(reply);
                    currentRequest = null;
                }
            } catch (FIPAException e) {
                e.printStackTrace();
                currentRequest = null;
            }
        }
    }

    private class WaitForRoadResponsesBehaviour extends CyclicBehaviour {
        private final long TIMEOUT = 30000; // 30 секунд

        public void action() {
            if (currentRequest == null) {
                block(1000);
                return;
            }

            // Проверяем таймаут
            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
                System.out.println(agentId + ": Timeout waiting for road responses. Received " +
                        roadProposals.size() + " of " + expectedRoadResponses);

                if (roadProposals.size() > 0) {
                    selectBestRoadProposal();
                } else {
                    System.out.println(agentId + ": No road proposals received");
                    // Отправляем отказ вагону
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_ROAD_RESPONSE");
                    myAgent.send(reply);
                }

                resetState();
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
                    double cost = Double.parseDouble(parts[1]);
                    String routeId = parts[2];

                    Proposal proposal = new Proposal(sender, routeId, availableTime, cost, true);
                    roadProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from road " + sender);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    roadProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from road " + sender);
                }

                // Проверяем, получили ли все ответы
                if (roadProposals.size() >= expectedRoadResponses) {
                    System.out.println(agentId + ": Received all " + roadProposals.size() + " road responses");
                    selectBestRoadProposal();
                    resetState();
                }
            } else {
                block(1000);
            }
        }

        private void selectBestRoadProposal() {
            List<Proposal> allProposals = new ArrayList<>(roadProposals.values());
            bestRoadProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestRoadProposal != null) {
                System.out.println(agentId + ": Selected road " + bestRoadProposal.getResourceId() +
                        " with time: " + bestRoadProposal.getAvailableTime());

                // Рассчитываем итоговое предложение для вагона
                // Добавляем время на подготовку локомотива (15 минут)
                Date locoAvailableTime = TimeUtils.addMinutes(bestRoadProposal.getAvailableTime(), 15);
                // Рассчитываем стоимость (стоимость дороги + стоимость использования локомотива)
                double locoCost = 100.0; // Базовая стоимость использования локомотива
                double totalCost = bestRoadProposal.getCost() + locoCost;

                // Отправляем предложение вагону
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(locoAvailableTime.getTime() + ":" + totalCost + ":" + locomotive.getId());
                myAgent.send(reply);
                System.out.println(agentId + ": Sent proposal to wagon for time: " + locoAvailableTime + ", cost: " + totalCost);

                // Отправляем подтверждение выбранной дороге
                ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                acceptMsg.addReceiver(new jade.core.AID(bestRoadProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                acceptMsg.setContent("ACCEPT_PROPOSAL:" + currentRequest.cargoId);
                myAgent.send(acceptMsg);

                // Отправляем отказы остальным дорогам
                for (Map.Entry<String, Proposal> entry : roadProposals.entrySet()) {
                    if (!entry.getKey().equals(bestRoadProposal.getAgentId())) {
                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        rejectMsg.addReceiver(new jade.core.AID(entry.getKey(), jade.core.AID.ISLOCALNAME));
                        rejectMsg.setContent("REJECT_PROPOSAL:" + currentRequest.cargoId);
                        myAgent.send(rejectMsg);
                    }
                }
            } else {
                System.out.println(agentId + ": No suitable road found!");
                // Отправляем отказ вагону
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_SUITABLE_ROAD");
                myAgent.send(reply);
            }
        }

        private void resetState() {
            roadProposals.clear();
            roadAgentsContacted.clear();
            expectedRoadResponses = 0;
            bestRoadProposal = null;
            currentRequest = null;
        }
    }

    private class BookingConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_CREATED:")) {
                    String scheduleId = content.substring("SCHEDULE_CREATED:".length());

                    // Пересылаем подтверждение вагону
                    try {
                        DFAgentDescription template = new DFAgentDescription();
                        ServiceDescription sd = new ServiceDescription();
                        sd.setType("wagon");
                        template.addServices(sd);
                        DFAgentDescription[] wagonAgents = DFService.search(myAgent, template);
                        if (wagonAgents.length > 0) {
                            ACLMessage forwardMsg = new ACLMessage(ACLMessage.CONFIRM);
                            forwardMsg.addReceiver(wagonAgents[0].getName());
                            forwardMsg.setContent("SCHEDULE_CREATED:" + scheduleId);
                            myAgent.send(forwardMsg);
                        }
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }

                    System.out.println(agentId + ": Schedule created, confirmation sent to wagon");
                }
            } else {
                block();
            }
        }
    }

    private class ScheduleFinalizationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_FINALIZED:")) {
                    String scheduleId = content.substring("SCHEDULE_FINALIZED:".length());
                    locomotive.setAvailable(false);
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