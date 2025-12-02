package clients;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import utils.DataLoader;
import utils.Config;
import java.util.List;

public class WagonClient {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : Config.MAIN_HOST;

        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, host);
        p.setParameter(Profile.MAIN_PORT, Config.MAIN_PORT);
        p.setParameter(Profile.LOCAL_HOST, Config.CLIENT_HOST);

        AgentContainer container = rt.createAgentContainer(p);

        try {
            List<String> agentIds = DataLoader.getAllWagonAgentIds();
            for (String agentId : agentIds) {
                AgentController agent = container.createNewAgent(
                        agentId, "agents.WagonAgent", new Object[]{agentId});
                agent.start();
            }
            System.out.println("WagonClient started " + agentIds.size() + " agents on " + host);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}