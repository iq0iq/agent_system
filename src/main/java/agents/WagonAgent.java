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
import utils.DataLoader;

public class WagonAgent extends Agent {
    private Wagon wagon;
    private String agentId;

    protected void setup() {
        agentId = (String) getArguments()[0];
        wagon = DataLoader.getWagonForAgent(agentId);

        if (wagon == null) {
            System.out.println(agentId + ": No wagon found!");
            doDelete();
            return;
        }

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("wagon");
        sd.setName("WagonService");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {}

        addBehaviour(new WagonRequestBehaviour());
        addBehaviour(new BookingConfirmationBehaviour());
        addBehaviour(new ScheduleConfirmationBehaviour());
        System.out.println(agentId + " started with wagon: " + wagon.getId());
    }

    private class ScheduleConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CONFIRM);
            ACLMessage msg = myAgent.receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                if (content.startsWith("SCHEDULE_CREATED:")) {
                    String scheduleId = content.substring("SCHEDULE_CREATED:".length());

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

                    System.out.println(agentId + ": Forwarding schedule confirmation to cargo");
                }
            } else {
                block();
            }
        }
    }

    private class WagonRequestBehaviour extends CyclicBehaviour {
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
            System.out.println(agentId + ": Handling transport request: " + content);

            String[] parts = content.substring("TRANSPORT_REQUEST:".length()).split(":");
            String cargoType = parts[0];
            double weight = Double.parseDouble(parts[1]);
            String fromStation = parts[2];
            String toStation = parts[3];
            String cargoId = parts[4];

            if (wagon.canCarryCargo(cargoType, weight) && wagon.getCurrentStation().equals(fromStation)) {
                System.out.println(agentId + ": Can carry cargo " + cargoType + " weight " + weight + ", requesting locomotive");

                try {
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("locomotive");
                    template.addServices(sd);
                    DFAgentDescription[] locoAgents = DFService.search(myAgent, template);
                    if (locoAgents.length > 0) {
                        for (int i = 0; i < locoAgents.length; i++) {
                            ACLMessage forwardMsg = new ACLMessage(ACLMessage.REQUEST);
                            forwardMsg.addReceiver(locoAgents[i].getName());
                            forwardMsg.setContent("TRANSPORT_REQUEST:" + cargoType + ":" + weight +
                                    ":" + fromStation + ":" + toStation + ":" + wagon.getId() +
                                    ":" + cargoId);
                            myAgent.send(forwardMsg);
                            System.out.println(agentId + ": Forwarded request to locomotive: " + locoAgents[i].getName().getLocalName());
                        }

                    } else {
                        System.out.println(agentId + ": No locomotive agents found!");
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.FAILURE);
                        reply.setContent("NO_LOCOMOTIVE_AVAILABLE");
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
                System.out.println(agentId + ": Cannot carry cargo - type: " + cargoType + ", weight: " + weight + ", capacity: " + wagon.getCapacity());
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("CANNOT_CARRY_CARGO");
                myAgent.send(reply);
            }
        }

        private void handleReserveBooking(ACLMessage msg, String content) {
            String scheduleId = content.substring("RESERVE_BOOKING:".length());

            wagon.setAvailable(false);

            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.CONFIRM);
            reply.setContent("BOOKING_RESERVED:" + scheduleId + ":" + wagon.getId());
            myAgent.send(reply);

            System.out.println(agentId + ": Wagon booked for schedule: " + scheduleId);
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