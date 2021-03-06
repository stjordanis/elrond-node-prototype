package network.elrond.processor.impl.initialization;

import network.elrond.Application;
import network.elrond.application.AppContext;
import network.elrond.application.AppState;
import network.elrond.p2p.model.P2PConnection;
import network.elrond.p2p.model.P2PIntroductionMessage;
import network.elrond.processor.AppTask;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class P2PConnectionStarterProcessor implements AppTask {

    private static final Logger logger = LogManager.getLogger(P2PConnectionStarterProcessor.class);

    @Override
    public void process(Application application) throws IOException {
        logger.traceEntry("params: {}", application);
        AppContext context = application.getContext();
        AppState state = application.getState();

        P2PConnection connection = AppServiceProvider.getP2PConnectionService().createConnection(context);

        connection.setShard(state.getShard());
        state.setConnection(connection);

        logger.info("Peer {}", connection.getPeer().peerAddress());
        logger.info("Allocated to shard {}", state.getShard().getIndex());

        P2PIntroductionMessage message = new P2PIntroductionMessage(connection.getPeer().peerAddress(), connection.getShard().getIndex());
        AppServiceProvider.getP2PConnectionService().broadcastMessage(message, connection);

        logger.traceExit();
    }

}
