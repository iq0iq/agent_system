package agents;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;
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

        addBehaviour(new RequestWagonsBehaviour());
        addBehaviour(new WaitForWagonResponsesBehaviour());
        addBehaviour(new ScheduleConfirmationBehaviour());
        System.out.println(agentId + " started with cargo: " + cargo.getId());
    }

    private class RequestWagonsBehaviour extends Behaviour {
        private boolean requestSent = false;

        public void action() {
            if (!requestSent) {
                try {
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("wagon");
                    template.addServices(sd);
                    DFAgentDescription[] wagonAgents = DFService.search(getAgent(), template);

                    if (wagonAgents.length > 0) {
                        for (DFAgentDescription desc : wagonAgents) {
                            wagonAgentsContacted.add(desc.getName().getLocalName());
                            ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                            msg.addReceiver(desc.getName());
                            msg.setContent("CARGO_REQUEST:" + cargo.getId() + ":" + cargo.getType() + ":" +
                                    cargo.getWeight() + ":" + cargo.getFromStation() + ":" +
                                    cargo.getToStation() + ":" + cargo.getPriority());
                            send(msg);
                            System.out.println(agentId + ": Sent cargo request to wagon: " + desc.getName().getLocalName());
                        }
                        expectedWagonResponses = wagonAgents.length;
                        startTime = System.currentTimeMillis();
                        requestSent = true;
                    } else {
                        System.out.println(agentId + ": No wagon agents found!");
                        requestSent = true;
                    }
                } catch (FIPAException e) {
                    e.printStackTrace();
                }
            }
        }

        public boolean done() {
            return requestSent;
        }
    }

    private class WaitForWagonResponsesBehaviour extends CyclicBehaviour {
        private final long TIMEOUT = 60000; // 60 секунд

        public void action() {
            // Проверяем таймаут
            if ((System.currentTimeMillis() - startTime) > TIMEOUT) {
                System.out.println(agentId + ": Timeout waiting for wagon responses. Received " +
                        wagonProposals.size() + " of " + expectedWagonResponses);

                if (wagonProposals.size() > 0) {
                    selectBestProposal();
                } else {
                    System.out.println(agentId + ": No wagon proposals received");
                    // Можем попробовать снова через некоторое время
                    getAgent().addBehaviour(new RequestWagonsBehaviour());
                }

                getAgent().removeBehaviour(this);
                return;
            }

            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                    MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
            );

            ACLMessage msg = receive(mt);

            if (msg != null) {
                String sender = msg.getSender().getLocalName();

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    String content = msg.getContent();
                    String[] parts = content.split(":");
                    Date availableTime = new Date(Long.parseLong(parts[0]));
                    double cost = Double.parseDouble(parts[1]);
                    String wagonId = parts[2];

                    Proposal proposal = new Proposal(sender, wagonId, availableTime, cost, true);
                    wagonProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from " + sender +
                            " - time: " + availableTime + ", cost: " + cost);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    wagonProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from " + sender +
                            " - reason: " + msg.getContent());
                }

                // Проверяем, получили ли все ответы
                if (wagonProposals.size() >= expectedWagonResponses) {
                    System.out.println(agentId + ": Received all " + wagonProposals.size() + " responses");
                    selectBestProposal();
                    getAgent().removeBehaviour(this);
                }
            } else {
                block(1000);
            }
        }

        private void selectBestProposal() {
            List<Proposal> allProposals = new ArrayList<>(wagonProposals.values());
            bestWagonProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestWagonProposal != null) {
                System.out.println(agentId + ": Selected wagon " + bestWagonProposal.getResourceId() +
                        " with time: " + bestWagonProposal.getAvailableTime());

                // Отправляем подтверждение выбранному вагону
                ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                acceptMsg.addReceiver(new jade.core.AID(bestWagonProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargo.getId());
                send(acceptMsg);
                System.out.println(agentId + ": Sent acceptance to " + bestWagonProposal.getAgentId());

                // Отправляем отказы остальным вагонам
                for (Map.Entry<String, Proposal> entry : wagonProposals.entrySet()) {
                    if (!entry.getKey().equals(bestWagonProposal.getAgentId())) {
                        ACLMessage rejectMsg = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                        rejectMsg.addReceiver(new jade.core.AID(entry.getKey(), jade.core.AID.ISLOCALNAME));
                        rejectMsg.setContent("REJECT_PROPOSAL:" + cargo.getId());
                        send(rejectMsg);
                    }
                }
            } else {
                System.out.println(agentId + ": No suitable wagon found!");
            }
        }
    }

    private class ScheduleConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_CONFIRMED:")) {
                    String scheduleId = content.substring("SCHEDULE_CONFIRMED:".length());
                    cargo.setStatus("SCHEDULED");
                    System.out.println(agentId + ": Schedule confirmed: " + scheduleId + ", cargo status: " + cargo.getStatus());

                    // Уведомляем всех участников о финализации расписания
                    confirmToAllParticipants(scheduleId);
                }
            } else {
                block();
            }
        }

        private void confirmToAllParticipants(String scheduleId) {
            try {
                String[] agentTypes = {"wagon", "locomotive", "road"};
                for (String type : agentTypes) {
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType(type);
                    template.addServices(sd);
                    DFAgentDescription[] agents = DFService.search(getAgent(), template);
                    for (DFAgentDescription desc : agents) {
                        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                        msg.addReceiver(desc.getName());
                        msg.setContent("SCHEDULE_FINALIZED:" + scheduleId);
                        send(msg);
                    }
                }
                System.out.println(agentId + ": Sent schedule finalized notification to all participants");
            } catch (FIPAException e) {
                e.printStackTrace();
            }
        }
    }

    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
        System.out.println(agentId + " terminated");
    }
}
