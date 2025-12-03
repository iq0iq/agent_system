package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import models.Cargo;
import models.Proposal;
import utils.DataLoader;
import utils.TimeUtils;
import java.util.*;

public class CargoAgent extends Agent {
    private Cargo cargo;
    private String agentId;
    private Map<String, Proposal> wagonProposals = new HashMap<>();
    private List<String> wagonAgentsContacted = new ArrayList<>();
    private int expectedWagonResponses = 0;
    private Proposal bestWagonProposal = null;
    private long startTime;
    private boolean isProcessing = false;

    protected void setup() {
        agentId = (String) getArguments()[0];
        cargo = DataLoader.getCargoForAgent(agentId);

        if (cargo == null) {
            System.out.println(agentId + ": No cargo found!");
            doDelete();
            return;
        }

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("cargo");
        sd.setName("CargoService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        System.out.println(agentId + " started with cargo: " + cargo.getId() +
                " at station: " + cargo.getFromStation());

        addBehaviour(new RequestWagonsBehaviour(this, 3000));
//        addBehaviour(new WaitForWagonResponsesBehaviour());
//        addBehaviour(new ScheduleConfirmationBehaviour());
    }

    private class RequestWagonsBehaviour extends TickerBehaviour {
        private boolean requestSent = false;

        public RequestWagonsBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            if (!requestSent && !isProcessing) {
                try {
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("wagon");
                    template.addServices(sd);
                    DFAgentDescription[] wagonAgents = DFService.search(getAgent(), template);

                    if (wagonAgents.length > 0) {
                        isProcessing = true;
                        for (DFAgentDescription desc : wagonAgents) {
                            wagonAgentsContacted.add(desc.getName().getLocalName());
                            ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                            msg.addReceiver(desc.getName());
                            msg.setContent("CARGO_REQUEST:" + cargo.getId() + ":" + cargo.getType() + ":" +
                                    cargo.getWeight() + ":" + cargo.getFromStation() + ":" +
                                    cargo.getToStation() + ":" +
                                    DataLoader.getSimulationStartTime().getTime());
                            send(msg);
                            System.out.println(agentId + ": Sent cargo request to wagon: " + desc.getName().getLocalName());
                        }
                        expectedWagonResponses = wagonAgents.length;
                        startTime = System.currentTimeMillis();
                        requestSent = true;
                        System.out.println(agentId + ": Sent requests to " + wagonAgents.length + " wagon agents");
                        stop();
                    } else {
                        System.out.println(agentId + ": No wagon agents found! Next check in " + (getPeriod()/1000) + " seconds");
                    }
                } catch (FIPAException e) {
                    e.printStackTrace();
                }
            }
        }
    }

//    private class WaitForWagonResponsesBehaviour extends CyclicBehaviour {
//        private final long TIMEOUT = 60000; // 60 секунд
//
//        public void action() {
//            if (!isProcessing) {
//                block(1000);
//                return;
//            }
//
//            if (wagonProposals.size() >= expectedWagonResponses) {
//                processProposals();
//                return;
//            }
//
//            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
//                System.out.println(agentId + ": Timeout waiting for wagon responses. Received " +
//                        wagonProposals.size() + " of " + expectedWagonResponses);
//                processProposals();
//                return;
//            }
//
//            MessageTemplate mt = MessageTemplate.or(
//                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
//                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
//            );
//
//            ACLMessage msg = receive(mt);
//
//            if (msg != null) {
//                String sender = msg.getSender().getLocalName();
//
//                if (msg.getPerformative() == ACLMessage.PROPOSE) {
//                    String content = msg.getContent();
//                    String[] parts = content.split(":");
//                    Date availableTime = new Date(Long.parseLong(parts[0]));
//                    String wagonId = parts[1];
//
//                    Proposal proposal = new Proposal(sender, wagonId, availableTime, true);
//                    wagonProposals.put(sender, proposal);
//                    System.out.println(agentId + ": Received proposal from " + sender +
//                            " - time: " + availableTime);
//                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
//                    Proposal proposal = new Proposal(sender, msg.getContent());
//                    wagonProposals.put(sender, proposal);
//                    System.out.println(agentId + ": Received refusal from " + sender);
//                }
//            } else {
//                block(1000);
//            }
//        }
//
//        private void processProposals() {
//            System.out.println(agentId + ": Received " + wagonProposals.size() + " responses");
//
//            List<Proposal> allProposals = new ArrayList<>(wagonProposals.values());
//            bestWagonProposal = TimeUtils.selectBestProposal(allProposals);
//
//            if (bestWagonProposal != null) {
//                System.out.println(agentId + ": Selected wagon " + bestWagonProposal.getResourceId() +
//                        " with time: " + bestWagonProposal.getAvailableTime());
//
//                // Бронируем выбранный вагон
//                ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
//                acceptMsg.addReceiver(new jade.core.AID(bestWagonProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
//                acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargo.getId() + ":" + cargo.getToStation());
//                send(acceptMsg);
//                System.out.println(agentId + ": Sent acceptance to " + bestWagonProposal.getAgentId());
//
//                // Отправляем отказы остальным вагонам
//                for (Map.Entry<String, Proposal> entry : wagonProposals.entrySet()) {
//                    if (!entry.getKey().equals(bestWagonProposal.getAgentId())) {
//                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
//                        rejectMsg.addReceiver(new jade.core.AID(entry.getKey(), jade.core.AID.ISLOCALNAME));
//                        rejectMsg.setContent("REJECT_PROPOSAL:" + cargo.getId());
//                        send(rejectMsg);
//                    }
//                }
//            } else {
//                System.out.println(agentId + ": No suitable wagon found!");
//            }
//
//            // Сбрасываем состояние
//            wagonProposals.clear();
//            wagonAgentsContacted.clear();
//            expectedWagonResponses = 0;
//            isProcessing = false;
//        }
//    }
//
//    private class ScheduleConfirmationBehaviour extends CyclicBehaviour {
//        public void action() {
//            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
//            ACLMessage msg = receive(mt);
//
//            if (msg != null) {
//                String content = msg.getContent();
//                if (content.startsWith("SCHEDULE_CONFIRMED:")) {
//                    String[] parts = content.substring("SCHEDULE_CONFIRMED:".length()).split(":");
//                    String scheduleId = parts[0];
//                    Date departureTime = new Date(Long.parseLong(parts[1]));
//                    Date arrivalTime = new Date(Long.parseLong(parts[2]));
//
//                    System.out.println(agentId + ": Schedule confirmed: " + scheduleId +
//                            ", departure: " + departureTime +
//                            ", arrival: " + arrivalTime);
//                }
//            } else {
//                block();
//            }
//        }
//    }
//
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated");
    }
}