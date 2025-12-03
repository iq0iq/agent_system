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
    private String currentCargoToStation = null;
    private long startTime;

    private class CargoRequest {
        String cargoId;
        String cargoType;
        double weight;
        String fromStation;
        String toStation;
        Date wagonAvailableTime; // Свободное время вагона
        ACLMessage originalMessage;

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
//        addBehaviour(new WaitForLocomotiveResponsesBehaviour());
//        addBehaviour(new AcceptProposalBehaviour());
//        addBehaviour(new RejectProposalBehaviour());
        System.out.println(agentId + " started with wagon: " + wagon.getId() +
                " at station: " + wagon.getCurrentStation() +
                ", schedule size: " + scheduleData.getSchedule().size());
    }

    private class CargoRequestBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null && currentRequest == null) {
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
            Date cargoAppearanceTime = new Date(Long.parseLong(parts[5]));

            // ВАЖНОЕ ИЗМЕНЕНИЕ: вычисляем свободное время вагона
            Date wagonAvailableTime = calculateWagonAvailableTime(cargoAppearanceTime);

            currentRequest = new CargoRequest(cargoId, cargoType, weight, fromStation,
                    toStation, wagonAvailableTime, msg);

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

            System.out.println(agentId + ": Cargo " + cargoId + " compatible, wagon available at: " +
                    wagonAvailableTime + ", requesting locomotives");
            requestLocomotives();
        }

        private Date calculateWagonAvailableTime(Date requestedTime) {
            // Если расписание пустое, вагон свободен с requestedTime
            if (scheduleData.getSchedule().isEmpty()) {
                return requestedTime;
            }

            // Получаем последний занятый временной слот
            List<TimeSlot> schedule = scheduleData.getSchedule();
            TimeSlot lastSlot = schedule.get(schedule.size() - 1);

            // Если requestedTime позже, чем время окончания последнего слота,
            // то вагон свободен с requestedTime
            if (requestedTime.after(lastSlot.getEndTime())) {
                return requestedTime;
            }

            // Иначе вагон будет свободен после окончания последнего слота
            return lastSlot.getEndTime();
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

                        // ВАЖНОЕ ИЗМЕНЕНИЕ: отправляем свободное время вагона, а не время появления груза
                        msg.setContent("WAGON_REQUEST:" + currentRequest.cargoId + ":" +
                                currentRequest.cargoType + ":" + currentRequest.weight + ":" +
                                currentRequest.fromStation + ":" + currentRequest.toStation + ":" +
                                wagon.getId() + ":" + wagon.getCapacity() + ":" +
                                currentRequest.wagonAvailableTime.getTime()); // Используем wagonAvailableTime

                        myAgent.send(msg);
                        System.out.println(agentId + ": Sent request to locomotive: " + desc.getName().getLocalName() +
                                " with wagon available time: " + currentRequest.wagonAvailableTime);
                    }
                    expectedLocomotiveResponses = locoAgents.length;
                    startTime = System.currentTimeMillis();
                    System.out.println(agentId + ": Sent requests to " + locoAgents.length + " locomotive agents");
                } else {
                    System.out.println(agentId + ": No locomotive agents found!");
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

            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
                System.out.println(agentId + ": Timeout waiting for locomotive responses. Received " +
                        locomotiveProposals.size() + " of " + expectedLocomotiveResponses);

                if (locomotiveProposals.size() > 0) {
                    processLocomotiveProposals();
                } else {
                    System.out.println(agentId + ": No locomotive proposals received");
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
                    String locomotiveId = parts[1];
                    String cargoId = parts.length > 2 ? parts[2] : null;

                    if (!currentRequest.cargoId.equals(cargoId)) {
                        System.out.println(agentId + ": Received proposal for wrong cargo: " + cargoId);
                        ACLMessage refuseMsg = new ACLMessage(ACLMessage.REFUSE);
                        refuseMsg.addReceiver(msg.getSender());
                        refuseMsg.setContent("WRONG_CARGO");
                        myAgent.send(refuseMsg);
                        return;
                    }

                    Proposal proposal = new Proposal(sender, locomotiveId, availableTime, true);
                    locomotiveProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from locomotive " + sender);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    locomotiveProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from locomotive " + sender);
                }

                if (locomotiveProposals.size() >= expectedLocomotiveResponses) {
                    System.out.println(agentId + ": Received all " + locomotiveProposals.size() + " locomotive responses");
                    processLocomotiveProposals();
                    resetState();
                }
            } else {
                block(1000);
            }
        }

        private void processLocomotiveProposals() {
            List<Proposal> allProposals = new ArrayList<>(locomotiveProposals.values());
            bestLocomotiveProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestLocomotiveProposal != null) {
                System.out.println(agentId + ": Selected locomotive " + bestLocomotiveProposal.getResourceId() +
                        " with time: " + bestLocomotiveProposal.getAvailableTime());

                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(bestLocomotiveProposal.getAvailableTime().getTime() + ":" + wagon.getId());
                myAgent.send(reply);
                System.out.println(agentId + ": Sent proposal to cargo for time: " + bestLocomotiveProposal.getAvailableTime());
            } else {
                System.out.println(agentId + ": No suitable locomotive found!");
                ACLMessage reply = currentRequest.originalMessage.createReply();
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("NO_SUITABLE_LOCOMOTIVE");
                myAgent.send(reply);
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

                    if (bestLocomotiveProposal != null) {
                        // Бронируем выбранный локомотив
                        ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                        acceptMsg.addReceiver(new jade.core.AID(bestLocomotiveProposal.getAgentId(), jade.core.AID.ISLOCALNAME));

                        if (departureTimeStr != null) {
                            acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargoId + ":" + toStation + ":" + departureTimeStr);
                        } else {
                            acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargoId + ":" + toStation);
                        }

                        myAgent.send(acceptMsg);

                        currentCargoId = cargoId;
                        currentCargoToStation = toStation;

                        System.out.println(agentId + ": Accepted locomotive " + bestLocomotiveProposal.getAgentId() +
                                " for cargo " + cargoId);
                    }
                } else if (content.startsWith("SCHEDULE_CREATED:")) {
                    String[] parts = content.substring("SCHEDULE_CREATED:".length()).split(":");
                    String scheduleId = parts[0];
                    Date departureTime = new Date(Long.parseLong(parts[1]));
                    Date arrivalTime = new Date(Long.parseLong(parts[2]));

                    // Резервируем время для вагона
                    scheduleData.reserveTimeSlot(departureTime, arrivalTime);

                    // Отправляем подтверждение грузу
                    try {
                        DFAgentDescription template = new DFAgentDescription();
                        ServiceDescription sd = new ServiceDescription();
                        sd.setType("cargo");
                        template.addServices(sd);
                        DFAgentDescription[] cargoAgents = DFService.search(myAgent, template);
                        for (DFAgentDescription desc : cargoAgents) {
                            ACLMessage confirmMsg = new ACLMessage(ACLMessage.CONFIRM);
                            confirmMsg.addReceiver(desc.getName());
                            confirmMsg.setContent("SCHEDULE_CONFIRMED:" + scheduleId + ":" +
                                    departureTime.getTime() + ":" + arrivalTime.getTime());
                            myAgent.send(confirmMsg);
                        }
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }

                    System.out.println(agentId + ": Schedule created: " + scheduleId);

                    // После доставки освобождаем вагон и меняем станцию
                    wagon.setCurrentStation(currentCargoToStation);
                    wagon.setAvailable(true);
                    System.out.println(agentId + ": Wagon moved to station: " + currentCargoToStation);

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

                    if (bestLocomotiveProposal != null) {
                        // Отменяем бронирование локомотива
                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        rejectMsg.addReceiver(new jade.core.AID(bestLocomotiveProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                        rejectMsg.setContent("REJECT_PROPOSAL:" + cargoId);
                        myAgent.send(rejectMsg);

                        System.out.println(agentId + ": Rejected by cargo " + cargoId + ", canceling locomotive");
                    }

                    resetState();
                }
            } else {
                block();
            }
        }
    }

    private void resetState() {
        locomotiveProposals.clear();
        locomotiveAgentsContacted.clear();
        expectedLocomotiveResponses = 0;
        bestLocomotiveProposal = null;
        currentRequest = null;
        currentCargoId = null;
        currentCargoToStation = null;
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated at station: " + wagon.getCurrentStation());
    }
}