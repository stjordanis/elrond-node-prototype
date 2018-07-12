package network.elrond.blockchain;

import network.elrond.p2p.P2PRequestChannel;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

public interface BlockchainService {

    <H extends Object, B extends Serializable> boolean contains(H hash, Blockchain blockchain, BlockchainUnitType type) throws IOException, ClassNotFoundException;

    // <H extends Object, B extends Serializable> void putAndWait(H hash, B object, Blockchain blockchain, BlockchainUnitType type) throws IOException;

    <H extends Object, B extends Serializable> void put(H hash, B object, Blockchain blockchain, BlockchainUnitType type) throws IOException;

    <H extends Object, B extends Serializable> B get(H hash, Blockchain blockchain, BlockchainUnitType type) throws IOException, ClassNotFoundException;

    <H extends Object, B extends Serializable> List<B> getAll(List<H> hashes, Blockchain blockchain, BlockchainUnitType type) throws IOException, ClassNotFoundException;
}
