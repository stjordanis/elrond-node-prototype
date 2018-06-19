package network.elrond;

import network.elrond.application.AppContext;
import network.elrond.core.Util;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.data.BootstrapType;

public class ContextCreator {

    protected static final String mintAddress = "026c00d83e0dc47e6b626ed6c42f636b";

    public static AppContext createAppContext(String nodeName, String nodePrivateKeyString, String masterPeerIpAddress,
                                              Integer masterPeerPort, Integer port, BootstrapType bootstrapType,
                                              String blockchainPath) {

        AppContext context = new AppContext();

        context.setMasterPeerIpAddress(masterPeerIpAddress);
        context.setMasterPeerPort(masterPeerPort);
        context.setPort(port);
        context.setNodeName(nodeName);
        context.setStorageBasePath(blockchainPath);

        context.setBootstrapType(bootstrapType);
        PrivateKey nodePrivateKey = new PrivateKey(Util.hexStringToByteArray(nodePrivateKeyString));
        context.setPrivateKey(nodePrivateKey);


        PublicKey mintPublicKey = new PublicKey(nodePrivateKey);
        String mintAddress = Util.getAddressFromPublicKey(mintPublicKey.getValue());
        context.setStrAddressMint(mintAddress);

        return context;
    }

}