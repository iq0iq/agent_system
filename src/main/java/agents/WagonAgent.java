package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
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
    private Wagon wagon;
    private String agentId;
    private ScheduleData scheduleData;
    private Map<String, Proposal> locomotiveProposals = new HashMap<>();
    private List<String> locomotiveAgentsContacted = new ArrayList<>();
    private int expectedLocomotiveResponses = 0;
    private Proposal bestLocomotiveProposal = null;
    private String currentCargoId = null;
    private long startTime;

    // Храним информацию о текущем запросе
    private class CargoRequest {
        String cargoId;
        String cargoType;
        double weight;
        String fromStation;
        String toStation;
        String priority;
        ACLMessage originalMessage;

        CargoRequest(String cargoId, String cargoType, double weight,
                     String fromStation, String toStation, String priority,
                     ACLMessage originalMessage) {
            this.cargoId = cargoId;
            this.cargoType = cargoType;
            this.weight = weight;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.priority = priority;
            this.originalMessage = originalMessage;
        }
    }

    private CargoRequest currentRequest = null;

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
        } catch (FIPAException e) {}

        addBehaviour(new CargoRequestBehaviour());
        addBehaviour(new WaitForLocomotiveResponsesBehaviour());
        addBehaviour(new BookingConfirmationBehaviour());
        addBehaviour(new ScheduleFinalizationBehaviour());
        System.out.println(agentId + " started with wagon: " + wagon.getId());
    }

    private class CargoRequestBehaviour extends CyclicBehaviour {
        public void action() {
            // Ждем запросы от грузов
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest == null) { // Обрабатываем только один запрос за раз
                String content = msg.getContent();
                if (content.startsWith("CARGO_REQUEST:")) {
                    handleCargoRequest(msg, content);
                }
            } else {
                block();
            }
        }

        private void handleCargoRequest(ACLMessage msg, String content) {
            String[] parts = content.substring("CARGO_REQUEST:".length()).split(":");
            String cargoId = parts[0];
            String cargoType = parts[1];
            double weight = Double.parseDouble(parts[2]);
            String fromStation = parts[3];
            String toStation = parts[4];
            String priority = parts[5];

            currentRequest = new CargoRequest(cargoId, cargoType, weight, fromStation, toStation, priority, msg);

            // Проверяем возможность перевозки
            if (!wagon.canCarryCargo(cargoType, weight)) {
                System.out.println(agentId + ": Cannot carry cargo " + cargoType + " weight " + weight);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("INCOMPATIBLE_CARGO_TYPE_OR_WEIGHT");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            if (!wagon.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Not at requested station. Current: " +
                        wagon.getCurrentStation() + ", Requested: " + fromStation);
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NOT_AT_REQUESTED_STATION");
                myAgent.send(reply);
                currentRequest = null;
                return;
            }

            // Если проверки прошли, запрашиваем локомотивы
            System.out.println(agentId + ": Cargo " + cargoId + " compatible, requesting locomotives");
            requestLocomotives();
        }

        private void requestLocomotives() {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("locomotive");
                template.addServices(sd);
                DFAgentDescription[] locoAgents = DFService.search(myAgent, template);

                if (locoAgents.length > 0) {
                    for (DFAgentDescription desc : locoAgents) {
                        locomotiveAgentsContacted.add(desc.getName().getLocalName());
                        ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                        msg.addReceiver(desc.getName());
                        msg.setContent("WAGON_REQUEST:" + currentRequest.cargoId + ":" +
                                currentRequest.cargoType + ":" + currentRequest.weight + ":" +
                                currentRequest.fromStation + ":" + currentRequest.toStation + ":" +
                                wagon.getId() + ":" + wagon.getCapacity() + ":" + currentRequest.priority);
                        myAgent.send(msg);
                        System.out.println(agentId + ": Sent request to locomotive: " + desc.getName().getLocalName());
                    }
                    expectedLocomotiveResponses = locoAgents.length;
                    startTime = System.currentTimeMillis();
                    System.out.println(agentId + ": Sent requests to " + locoAgents.length + " locomotive agents");
                } else {
                    System.out.println(agentId + ": No locomotive agents found!");
                    // Отправляем отказ грузу
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_LOCOMOTIVE_AVAILABLE");
                    myAgent.send(reply);
                    currentRequest = null;
                }
            } catch (FIPAException e) {
                e.printStackTrace();
                currentRequest = null;
            }
        }
    }

    private class WaitForLocomotiveResponsesBehaviour extends CyclicBehaviour {
        private final long TIMEOUT = 45000; // 45 секунд

        public void action() {
            if (currentRequest == null) {
                block(1000);
                return;
            }

            // Проверяем таймаут
            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
                System.out.println(agentId + ": Timeout waiting for locomotive responses. Received " +
                        locomotiveProposals.size() + " of " + expectedLocomotiveResponses);

                if (locomotiveProposals.size() > 0) {
                    selectBestLocomotiveProposal();
                } else {
                    System.out.println(agentId + ": No locomotive proposals received");
                    // Отправляем отказ грузу
                    ACLMessage reply = currentRequest.originalMessage.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("NO_LOCOMOTIVE_RESPONSE");
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
                    String locomotiveId = parts[2];

                    Proposal proposal = new Proposal(sender, locomotiveId, availableTime, cost, true);
                    locomotiveProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from locomotive " + sender);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    locomotiveProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from locomotive " + sender);
                }

                // Проверяем, получили ли все ответы
                if (locomotiveProposals.size() >= expectedLocomotiveResponses) {
                    System.out.println(agentId + ": Received all " + locomotiveProposals.size() + " locomotive responses");
                    selectBestLocomotiveProposal();
                    resetState();
                }
            } else {
                block(1000);
            }
        }

        private void selectBestLocomotiveProposal() {
            List<Proposal> allProposals = new ArrayList<>(locomotiveProposals.values());
            bestLocomotiveProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestLocomotiveProposal != null) {
                System.out.println(agentId + ": Selected locomotive " + bestLocomotiveProposal.getResourceId() +
                        " with time: " + bestLocomotiveProposal.getAvailableTime());

                // Рассчитываем итоговое предложение для груза
                // Добавляем время на погрузку/разгрузку (30 минут)
                Date wagonAvailableTime = TimeUtils.addMinutes(bestLocomotiveProposal.getAvailableTime(), 30);
                // Рассчитываем стоимость (стоимость локомотива + стоимость использования вагона)
                double wagonCost = 50.0; // Базовая стоимость использования вагона
                double totalCost = bestLocomotiveProposal.getCost() + wagonCost;

                // Отправляем предложение грузу
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(wagonAvailableTime.getTime() + ":" + totalCost + ":" + wagon.getId());
                myAgent.send(reply);
                System.out.println(agentId + ": Sent proposal to cargo for time: " + wagonAvailableTime + ", cost: " + totalCost);

                // Отправляем подтверждение выбранному локомотиву
                ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                acceptMsg.addReceiver(new jade.core.AID(bestLocomotiveProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                acceptMsg.setContent("ACCEPT_PROPOSAL:" + currentRequest.cargoId);
                myAgent.send(acceptMsg);

                // Отправляем отказы остальным локомотивам
                for (Map.Entry<String, Proposal> entry : locomotiveProposals.entrySet()) {
                    if (!entry.getKey().equals(bestLocomotiveProposal.getAgentId())) {
                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        rejectMsg.addReceiver(new jade.core.AID(entry.getKey(), jade.core.AID.ISLOCALNAME));
                        rejectMsg.setContent("REJECT_PROPOSAL:" + currentRequest.cargoId);
                        myAgent.send(rejectMsg);
                    }
                }
            } else {
                System.out.println(agentId + ": No suitable locomotive found!");
                // Отправляем отказ грузу
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_SUITABLE_LOCOMOTIVE");
                myAgent.send(reply);
            }
        }

        private void resetState() {
            locomotiveProposals.clear();
            locomotiveAgentsContacted.clear();
            expectedLocomotiveResponses = 0;
            bestLocomotiveProposal = null;
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

                    // Пересылаем подтверждение грузу
                    try {
                        DFAgentDescription template = new DFAgentDescription();
                        ServiceDescription sd = new ServiceDescription();
                        sd.setType("cargo");
                        template.addServices(sd);
                        DFAgentDescription[] cargoAgents = DFService.search(myAgent, template);
                        if (cargoAgents.length > 0) {
                            ACLMessage confirmMsg = new ACLMessage(ACLMessage.CONFIRM);
                            confirmMsg.addReceiver(cargoAgents[0].getName());
                            confirmMsg.setContent("SCHEDULE_CONFIRMED:" + scheduleId);
                            myAgent.send(confirmMsg);
                        }
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }

                    System.out.println(agentId + ": Schedule created, confirmation sent to cargo");
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
                    wagon.setAvailable(false);
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