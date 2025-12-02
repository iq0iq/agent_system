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
import utils.DataLoader;

public class CargoAgent extends Agent {
    private Cargo cargo;
    private String agentId;

    protected void setup() {
        agentId = (String) getArguments()[0];
        cargo = DataLoader.getCargoForAgent(agentId);

        if (cargo == null) {
            System.out.println(agentId + ": No cargo found!");
            doDelete();
            return;
        }

        // Register in DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("cargo");
        sd.setName("CargoService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        addBehaviour(new RequestWagonBehaviour());
        addBehaviour(new ScheduleConfirmationBehaviour());
        System.out.println(agentId + " started with cargo: " + cargo.getId());
    }

    private class RequestWagonBehaviour extends CyclicBehaviour {
        private boolean requestSent = false;
        private int attempts = 0;
        private long lastAttemptTime = 0;

        public void action() {
            if (!requestSent) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastAttemptTime > 3000) {
                    try {
                        DFAgentDescription template = new DFAgentDescription();
                        ServiceDescription sd = new ServiceDescription();
                        sd.setType("wagon");
                        template.addServices(sd);
                        DFAgentDescription[] wagonAgents = DFService.search(getAgent(), template);
                        if (wagonAgents.length > 0) {
                            for (int i = 0; i < wagonAgents.length; i++) {
                                ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
                                msg.addReceiver(wagonAgents[i].getName());
                                msg.setContent("TRANSPORT_REQUEST:" + cargo.getType() + ":" + cargo.getWeight() +
                                        ":" + cargo.getFromStation() + ":" + cargo.getToStation() +
                                        ":" + cargo.getId());
                                send(msg);
                                System.out.println(agentId + ": Sent transport request to wagon: " + wagonAgents[i].getName().getLocalName());
                                requestSent = true;
                            }
                        } else {
                            System.out.println(agentId + ": No wagon agents found! Attempt " + (++attempts) + ". Waiting...");
                            if (attempts >= 20) {
                                System.out.println(agentId + ": Giving up after 20 attempts");
                                requestSent = true;
                            }
                        }
                    } catch (FIPAException e) {
                        e.printStackTrace();
                        attempts++;
                    }
                    lastAttemptTime = currentTime;
                }
            } else {
                block(3000);
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

                    confirmToAllParticipants(scheduleId);
                }
            } else {
                block();
            }
        }

        private void confirmToAllParticipants(String scheduleId) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("wagon");
                template.addServices(sd);
                DFAgentDescription[] wagonAgents = DFService.search(getAgent(), template);
                for (DFAgentDescription desc : wagonAgents) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(desc.getName());
                    msg.setContent("BOOKING_CONFIRMED:" + scheduleId);
                    send(msg);
                }
                sd.setType("locomotive");
                template.addServices(sd);
                DFAgentDescription[] locoAgents = DFService.search(getAgent(), template);
                for (DFAgentDescription desc : locoAgents) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(desc.getName());
                    msg.setContent("BOOKING_CONFIRMED:" + scheduleId);
                    send(msg);
                }
                sd.setType("road");
                template.addServices(sd);
                DFAgentDescription[] roadAgents = DFService.search(getAgent(), template);
                for (DFAgentDescription desc : roadAgents) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(desc.getName());
                    msg.setContent("BOOKING_CONFIRMED:" + scheduleId);
                    send(msg);
                }

                System.out.println(agentId + ": Sent booking confirmation to all participants");
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