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
import utils.DataLoader;

public class LocomotiveAgent extends Agent {
    private Locomotive locomotive;
    private String agentId;
    private String wagonId;

    protected void setup() {
        agentId = (String) getArguments()[0];
        locomotive = DataLoader.getLocomotiveForAgent(agentId);

        if (locomotive == null) {
            System.out.println(agentId + ": No locomotive found!");
            doDelete();
            return;
        }

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("locomotive");
        sd.setName("LocomotiveService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        addBehaviour(new LocomotiveRequestBehaviour());
        addBehaviour(new BookingConfirmationBehaviour());
        addBehaviour(new ScheduleConfirmationBehaviour());
        System.out.println(agentId + " started with locomotive: " + locomotive.getId());
    }

    private class ScheduleConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                System.out.println(agentId + ": Received confirmation: " + msg.getContent());
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_CREATED:")) {
                    String scheduleId = content.substring("SCHEDULE_CREATED:".length());

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
                            System.out.println(agentId + ": Forwarding schedule confirmation to wagon: " + wagonAgents[0].getName().getLocalName());
                        } else {
                            System.out.println(agentId + ": No wagon agents found to forward confirmation!");
                        }
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                block();
            }
        }
    }

    private class LocomotiveRequestBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("TRANSPORT_REQUEST:")) {
                    handleTransportRequest(msg, content);
                } else if (content.startsWith("RESERVE_BOOKING:")) {
                    handleReserveBooking(msg, content);
                }
            } else {
                block();
            }
        }

        private void handleTransportRequest(ACLMessage msg, String content) {
            String[] parts = content.substring("TRANSPORT_REQUEST:".length()).split(":");
            String cargoType = parts[0];
            double weight = Double.parseDouble(parts[1]);
            String fromStation = parts[2];
            String toStation = parts[3];
            String wagonId = parts[4];
            String cargoId = parts[5];

            double totalWeight = weight;
            if (locomotive.canPullWeight(totalWeight) && locomotive.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Can pull weight, requesting road for " + wagonId + " " + cargoId);

                try {
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("road");
                    template.addServices(sd);
                    DFAgentDescription[] roadAgents = DFService.search(myAgent, template);
                    if (roadAgents.length > 0) {
                        for (int i = 0; i < roadAgents.length; i++) {
                            ACLMessage forwardMsg = new ACLMessage(ACLMessage.REQUEST);
                            forwardMsg.addReceiver(roadAgents[i].getName());
                            forwardMsg.setContent("TRANSPORT_REQUEST:" + fromStation + ":" + toStation +
                                    ":" + totalWeight + ":" + wagonId + ":" + locomotive.getId() +
                                    ":" + cargoId);
                            myAgent.send(forwardMsg);
                            System.out.println(agentId + ": Forwarded transport request to road agent: " + roadAgents[i].getName().getLocalName());
                        }

                    } else {
                        System.out.println(agentId + ": No road agents found!");
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.FAILURE);
                        reply.setContent("NO_ROAD_AGENT_AVAILABLE");
                        myAgent.send(reply);
                    }
                } catch (FIPAException e) {
                    e.printStackTrace();
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent("SEARCH_ERROR");
                    myAgent.send(reply);
                }
            } else {
                System.out.println(agentId + ": Cannot pull weight " + totalWeight + ", max capacity: " + locomotive.getMaxWeightCapacity());
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("INSUFFICIENT_POWER");
                myAgent.send(reply);
            }
        }

        private void handleReserveBooking(ACLMessage msg, String content) {
            String scheduleId = content.substring("RESERVE_BOOKING:".length());

            locomotive.setAvailable(false);

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setContent("BOOKING_RESERVED:" + scheduleId + ":" + locomotive.getId());
            myAgent.send(reply);

            System.out.println(agentId + ": Locomotive booked for schedule: " + scheduleId);
        }
    }

    private class BookingConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("BOOKING_CONFIRMED:")) {
                    String scheduleId = content.substring("BOOKING_CONFIRMED:".length());
                    System.out.println(agentId + ": Booking confirmed for schedule: " + scheduleId);
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