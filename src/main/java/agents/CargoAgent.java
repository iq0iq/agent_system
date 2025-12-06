package agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
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
    private boolean requestSent = false;
    private Date simulationStartTime;

    protected void setup() {
        agentId = (String) getArguments()[0];
        cargo = DataLoader.getCargoForAgent(agentId);
        simulationStartTime = DataLoader.getSimulationStartTime();

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
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error registering with DF: " + e.getMessage());
        }

        System.out.println(agentId + " started with cargo: " + cargo.getId() +
                " at station: " + cargo.getFromStation());

        addBehaviour(new RequestWagonsBehaviour(this, 4000));
        addBehaviour(new WaitForWagonResponsesBehaviour(this, 100));
        addBehaviour(new ScheduleConfirmationBehaviour(this, 100));
    }

    private class RequestWagonsBehaviour extends TickerBehaviour {
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
                    } else {
                        System.out.println(agentId + ": No wagon agents found! Next check in " + (getPeriod()/1000) + " seconds");
                    }
                } catch (FIPAException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class WaitForWagonResponsesBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
        );

        public WaitForWagonResponsesBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            if (!isProcessing) {
                return;
            }

            if (wagonProposals.size() >= expectedWagonResponses) {
                processProposals();
                return;
            }

            ACLMessage msg = receive(mt);

            if (msg != null) {
                String sender = msg.getSender().getLocalName();

                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    String content = msg.getContent();
                    String[] parts = content.split(":");
                    Date availableTime = new Date(Long.parseLong(parts[0]));
                    String wagonId = parts[1];

                    Proposal proposal = new Proposal(sender, wagonId, availableTime, true);
                    wagonProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received proposal from " + sender +
                            " - time: " + availableTime);
                } else if (msg.getPerformative() == ACLMessage.REFUSE) {
                    Proposal proposal = new Proposal(sender, msg.getContent());
                    wagonProposals.put(sender, proposal);
                    System.out.println(agentId + ": Received refusal from " + sender);
                }
            }
        }

        private void processProposals() {
            System.out.println(agentId + ": Received " + wagonProposals.size() + " responses");

            List<Proposal> allProposals = new ArrayList<>(wagonProposals.values());
            bestWagonProposal = TimeUtils.selectBestProposal(allProposals);

            if (bestWagonProposal != null) {
                System.out.println(agentId + ": Selected wagon " + bestWagonProposal.getResourceId() +
                        " with time: " + bestWagonProposal.getAvailableTime());

                ACLMessage acceptMsg = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                acceptMsg.addReceiver(new jade.core.AID(bestWagonProposal.getAgentId(), jade.core.AID.ISLOCALNAME));
                acceptMsg.setContent("ACCEPT_PROPOSAL:" + cargo.getId() + ":" + cargo.getToStation());
                send(acceptMsg);
                System.out.println(agentId + ": Sent acceptance to " + bestWagonProposal.getAgentId());

            } else {
                System.out.println(agentId + ": No suitable wagon found!");
                requestSent = false;
                isProcessing = false;
            }

            wagonProposals.clear();
            wagonAgentsContacted.clear();
            expectedWagonResponses = 0;
            isProcessing = false;
        }
    }

    private class ScheduleConfirmationBehaviour extends TickerBehaviour {
        private MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL)
                )
        );

        public ScheduleConfirmationBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                System.out.println(agentId + ": Received schedule message: " + content);

                if (content.startsWith("SCHEDULE_CONFIRMED:")) {
                    String[] parts = content.substring("SCHEDULE_CONFIRMED:".length()).split(":");
                    String scheduleId = parts[0];
                    Date departureTime = new Date(Long.parseLong(parts[1]));
                    Date arrivalTime = new Date(Long.parseLong(parts[2]));

                    System.out.println("✅ " + agentId + ": Schedule confirmed: " + scheduleId +
                            ", departure: " + departureTime +
                            ", arrival: " + arrivalTime);
                } else if (content.startsWith("SCHEDULE_FINALIZED:")) {
                    String[] parts = content.substring("SCHEDULE_FINALIZED:".length()).split(":");
                    if (parts.length >= 3) {
                        String scheduleId = parts[0];
                        Date departureTime = new Date(Long.parseLong(parts[1]));
                        Date arrivalTime = new Date(Long.parseLong(parts[2]));

                        System.out.println("✅ " + agentId + ": Schedule FINALIZED: " + scheduleId +
                                ", departure: " + departureTime +
                                ", arrival: " + arrivalTime);
                    } else {
                        System.err.println(agentId + ": Invalid SCHEDULE_FINALIZED format: " + content);
                    }
                } else if (content.startsWith("ROAD_REJECTED:")) {
                    // Обработка отказа от дороги
                    String[] parts = content.substring("ROAD_REJECTED:".length()).split(":");
                    String reason = parts[0];
                    String locomotiveId = parts.length > 1 ? parts[1] : "";

                    System.out.println("❌ " + agentId + ": Road rejected schedule. Reason: " +
                            reason + ", locomotive: " + locomotiveId);

                    // Сбрасываем состояние, чтобы начать новый поиск
                    requestSent = false;
                    isProcessing = false;
                    bestWagonProposal = null;

                    System.out.println(agentId + ": Will retry scheduling cargo");
                } else if (content.startsWith("LOCOMOTIVE_REJECTED:")) {
                String[] parts = content.substring("LOCOMOTIVE_REJECTED:".length()).split(":");
                String reason = parts[0];
                String locomotiveId = parts.length > 1 ? parts[1] : "";

                System.out.println("❌ " + agentId + ": Locomotive rejected schedule. Reason: " +
                        reason + ", locomotive: " + locomotiveId);
                requestSent = false;
                isProcessing = false;
                bestWagonProposal = null;
                }
            }
        }
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            System.err.println(agentId + ": Error deregistering from DF: " + e.getMessage());
        }
        System.out.println(agentId + " terminated");
    }
}