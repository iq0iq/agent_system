package server;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import utils.DataLoader;
import utils.Config;

import java.io.FileWriter;
import java.util.List;

public class Server {
    public static void main(String[] args) {
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, Config.MAIN_HOST);
        p.setParameter(Profile.MAIN_PORT, Config.MAIN_PORT);
        p.setParameter(Profile.PLATFORM_ID, Config.PLATFORM_ID);

        AgentContainer mainContainer = rt.createMainContainer(p);

        try {
            String filename = "schedules.json";
            FileWriter writer = new FileWriter(filename, false);

            List<String> roadAgentIds = DataLoader.getAllRoadAgentIds();
            for (String agentId : roadAgentIds) {
                AgentController roadAgent = mainContainer.createNewAgent(
                        agentId, "agents.RoadAgent", new Object[]{agentId});
                roadAgent.start();
                System.out.println("Started RoadAgent: " + agentId);
            }

            System.out.println("Railway Server started with " + roadAgentIds.size() + " road agents");
            System.out.println("Platform: " + Config.MAIN_HOST + ":" + Config.MAIN_PORT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}