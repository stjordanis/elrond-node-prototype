package network.elrond.data;

import javafx.util.Pair;
import network.elrond.TimeWatch;
import network.elrond.account.Accounts;
import network.elrond.application.AppState;
import network.elrond.benchmark.Statistic;
import network.elrond.blockchain.Blockchain;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.chronology.ChronologyService;
import network.elrond.chronology.NTPClient;
import network.elrond.chronology.Round;
import network.elrond.chronology.RoundState;
import network.elrond.core.AsciiTableUtil;
import network.elrond.core.ObjectUtil;
import network.elrond.core.Util;
import network.elrond.crypto.*;
import network.elrond.p2p.P2PBroadcastChanel;
import network.elrond.p2p.P2PBroadcastChannelName;
import network.elrond.service.AppServiceProvider;
import network.elrond.sharding.Shard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;


//TODO: remove from "data" package
public class AppBlockManager {
    private static final Logger logger = LogManager.getLogger(AppBlockManager.class);

    private static AppBlockManager instance = new AppBlockManager();

    public static AppBlockManager instance() {
        return instance;
    }

    public Block generateAndBroadcastBlock(List<String> queue, PrivateKey privateKey, AppState state) {
        logger.traceEntry("params: {} {} {}", queue, privateKey, state);
        Accounts accounts = state.getAccounts();
        Blockchain blockchain = state.getBlockchain();

        List<String> hashes = new ArrayList<>(queue);

        if (hashes.isEmpty()) {
            logger.info("Can't execute, no transaction!");
            state.getStatisticsManager().addStatistic(new Statistic(0));
            return null;
        }

        try {
            List<Transaction> transactions = AppServiceProvider.getBlockchainService().getAll(hashes, blockchain, BlockchainUnitType.TRANSACTION);
            Pair<Block, List<Receipt>> blockReceiptsPair = composeBlock(transactions, state);
            Block block = blockReceiptsPair.getKey();
            List<Receipt> receipts = blockReceiptsPair.getValue();
            AppBlockManager.instance().signBlock(block, privateKey);

            String hashBlock = AppServiceProvider.getSerializationService().getHashString(block);

            logger.debug("signed block with hash: {}", hashBlock);
            ExecutionService executionService = AppServiceProvider.getExecutionService();

            ExecutionReport result = AppServiceProvider.getBootstrapService().commitBlock(block, hashBlock, blockchain);

            if (!result.isOk()){
                logger.debug("Could not commit block with hash {}", hashBlock);
                return logger.traceExit((Block) null);
            }

            result = executionService.processBlock(block, accounts, blockchain, state.getStatisticsManager());
            Shard shard = blockchain.getShard();
            logger.debug("executed block with hash: {}", hashBlock);

            if (result.isOk()) {
                removeAlreadyProcessedTransactionsFromPool(state, block);

                logger.debug("removed {} transaction from pool", BlockUtil.getTransactionsCount(block));

                List<String> acceptedTransactions = BlockUtil.getTransactionsHashesAsString(block);

                List<Transaction> blockTransactions = AppServiceProvider.getBlockchainService()
                    .getAll(acceptedTransactions,
                            blockchain,
                            BlockchainUnitType.TRANSACTION);
                blockTransactions.stream()
                    .filter(transaction -> transaction.isCrossShardTransaction())
                        .filter(transaction -> !ObjectUtil.isEqual(shard, transaction.getReceiverShard()))
                    .forEach(transaction -> {
                        P2PBroadcastChanel channel = state.getChanel(P2PBroadcastChannelName.XTRANSACTION);
                        AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, transaction);
                    });

                logger.debug("sent Xtransactions");

                sendReceipts(state, block, receipts);

                logger.info("New block proposed with hash {}", hashBlock);

                logger.info("\n" + block.print().render());
                //logger.info("\n" + AsciiTableUtil.listToTables(transactions));
                logger.info("\n" + AsciiTableUtil.listToTables(accounts.getAddresses()
                        .stream()
                        .map(accountAddress -> {
                            try {
                                return AppServiceProvider.getAccountStateService().getAccountState(accountAddress, accounts);
                            } catch (IOException | ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())));
            }

            return logger.traceExit(block);
        } catch (IOException | ClassNotFoundException e) {
            logger.throwing(e);
        }

        return logger.traceExit((Block) null);
    }

    private void sendReceipts(AppState state, Block block, List<Receipt> receipts) throws IOException {
        Thread threadSend = new Thread(() -> {
            String hashBlock = AppServiceProvider.getSerializationService().getHashString(block);
            List<String> txHashes = new ArrayList<>();
            for (Receipt receipt : receipts) {
                // add the blockHash to the receipt as the valid hash is only available after signing
                receipt.setBlockHash(hashBlock);

                try {
                    sendReceipt(block, receipt, state);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                txHashes.add(receipt.getTransactionHash());
            }
        });
        threadSend.start();
    }

    public Pair<Block, List<Receipt>> composeBlock(List<Transaction> transactions, AppState state) throws
            IOException {
        logger.traceEntry("params: {} {}", transactions, state);
        Util.check(state != null, "state!=null");
        List<Receipt> receipts;

        Accounts accounts = state.getAccounts();
        Blockchain blockchain = state.getBlockchain();
        NTPClient ntpClient = state.getNtpClient();

        Util.check(transactions != null, "transactions!=null");
        Util.check(blockchain != null, "blockchain!=null");
        Util.check(accounts != null, "accounts!=null");
        Util.check(blockchain.getGenesisBlock() != null, "genesisBlock!=null");

        Block block = getNewBlockAndBindToPrevious(blockchain.getCurrentBlock());
        logger.trace("done generating blank new block as {}", block);

        ChronologyService chronologyService = AppServiceProvider.getChronologyService();
        long timestamp = blockchain.getGenesisBlock().getTimestamp();
        long synchronizedTime = chronologyService.getSynchronizedTime(ntpClient);
        Round round = chronologyService.getRoundFromDateTime(timestamp, synchronizedTime);
        block.setRoundIndex(round.getIndex());
        block.setTimestamp(round.getStartTimeStamp());
        logger.trace("done computing round and round start millis = calculated round start millis, round index = {}, time stamp = {}",
                block.roundIndex, block.timestamp);

        receipts = addTransactions(transactions, block, state);
        logger.trace("done added {} transactions to block", transactions.size());

        block.setAppStateHash(accounts.getAccountsPersistenceUnit().getRootHash());
        logger.trace("done added state root hash to block as {}", block.getAppStateHash());

        AppServiceProvider.getAccountStateService().rollbackAccountStates(accounts);
        logger.trace("reverted app state to original form");

        return logger.traceExit(new Pair<>(block, receipts));
    }

    private List<Receipt> addTransactions(List<Transaction> transactions, Block block, AppState state) throws
            IOException {
        logger.traceEntry("params: {} {} {}", transactions, block, state);
        Util.check(transactions != null, "transactions != null");
        Util.check(block != null, "block != null");
        Util.check(state != null, "state != null");

        List<Receipt> receipts = new ArrayList<>();

        Accounts accounts = state.getAccounts();
        TimeWatch tw = TimeWatch.start();
        for (Transaction transaction : transactions) {
            boolean valid = AppServiceProvider.getTransactionService().verifyTransaction(transaction);
            if (!valid) {
                receipts.add(rejectTransaction(block, transaction, state));
                logger.info("Invalid transaction discarded [verify] {}", transaction);
                continue;
            }

            ExecutionReport executionReport = AppServiceProvider.getExecutionService().processTransaction(transaction, accounts);
            if (!executionReport.isOk()) {
                receipts.add(rejectTransaction(block, transaction, state));
                logger.info("Invalid transaction discarded [exec] {}", transaction);
                continue;
            }

            byte[] txHash = AppServiceProvider.getSerializationService().getHash(transaction);
            receipts.add(acceptTransaction(block, transaction, state));

            logger.trace("added transaction {} in block", txHash);
            BlockUtil.addTransactionInBlock(block, txHash);

            //test whether the system should continue to add transactions or not
            boolean forceFinishAddingTransactions = !AppServiceProvider.getChronologyService().isStillInRoundState(state.getNtpClient(), state.getBlockchain().getGenesisBlock().getTimestamp(),
                    block.getRoundIndex(), RoundState.PROPOSE_BLOCK);
            if (forceFinishAddingTransactions){
                logger.debug("Force exit from add transactions method. Transactions added: {}", BlockUtil.getTransactionsCount(block));
                break;
            }
        }

        return logger.traceExit(receipts);
    }

    private Receipt acceptTransaction(Block block, Transaction transaction, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, transaction, state);
        Util.check(block != null, "block != null");
        Util.check(transaction != null, "transaction != null");
        Util.check(state != null, "state != null");

        ReceiptStatus status = ReceiptStatus.ACCEPTED;
        String log = "Transaction processed";
        String transactionHash = AppServiceProvider.getSerializationService().getHashString(transaction);
        Receipt receipt = new Receipt(null, transactionHash, status, log);

        return logger.traceExit(receipt);
    }

    private Receipt rejectTransaction(Block block, Transaction transaction, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, transaction, state);
        Util.check(block != null, "block != null");
        Util.check(transaction != null, "transaction != null");
        Util.check(state != null, "state != null");

        ReceiptStatus status = ReceiptStatus.REJECTED;
        String log = "Invalid transaction";
        String transactionHash = AppServiceProvider.getSerializationService().getHashString(transaction);
        Receipt receipt = new Receipt(null, transactionHash, status, log);

        return logger.traceExit(receipt);
    }



    private void sendReceipt(Block block, Receipt receipt, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, receipt, state);
        Util.check(block != null, "block != null");
        Util.check(receipt != null, "receipt != null");
        Util.check(state != null, "state != null");

        String blockHash = AppServiceProvider.getSerializationService().getHashString(block);
        String transactionHash = receipt.getTransactionHash();
        SecureObject<Receipt> secureReceipt = SecureObjectUtil.create(receipt, state.getPrivateKey(), state.getPublicKey());
        String secureReceiptHash = AppServiceProvider.getSerializationService().getHashString(secureReceipt);

        // Store on blockchain
        Blockchain blockchain = state.getBlockchain();
        AppServiceProvider.getBlockchainService().put(transactionHash, secureReceiptHash, blockchain, BlockchainUnitType.TRANSACTION_RECEIPT);
        AppServiceProvider.getBlockchainService().put(transactionHash, blockHash, blockchain, BlockchainUnitType.TRANSACTION_BLOCK);
        AppServiceProvider.getBlockchainService().put(secureReceiptHash, secureReceipt, blockchain, BlockchainUnitType.RECEIPT);
        logger.trace("placed on blockchain (TRANSACTION_RECEIPT, TRANSACTION_BLOCK and secure RECEIPT)");

        // Broadcast
        P2PBroadcastChanel channel = state.getChanel(P2PBroadcastChannelName.RECEIPT);
        AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, secureReceiptHash);
        logger.trace("broadcast the receipt hash");
        logger.traceExit();
    }

    private Block getNewBlockAndBindToPrevious(Block currentBlock) {
        logger.traceEntry("params: {}", currentBlock);
        Util.check(currentBlock != null, "currentBlock != null");

        Block block = new Block();
        byte[] hash = AppServiceProvider.getSerializationService().getHash(currentBlock);

        // Bind on prev block
        block.setPrevBlockHash(hash);
        BigInteger nonce = currentBlock.getNonce().add(BigInteger.ONE);
        block.setNonce(nonce);
        block.setShard(currentBlock.getShard());
        return logger.traceExit(block);
    }

    public void signBlock(Block block, PrivateKey privateKey) {
        logger.traceEntry("params: {} {}", block, privateKey);
        Util.check(block != null, "block != null");
        Util.check(privateKey != null, "privateKey != null");

        //AppContext context = application.getContext();

        block.listPubKeys.clear();
        block.listPubKeys.add(Util.byteArrayToHexString(new PublicKey(privateKey).getValue()));
        block.setCommitment(null);
        block.setSignature(null);
        logger.trace("set block's signature data to null!");

        ArrayList<byte[]> signersPublicKeys = new ArrayList<>();
        ArrayList<byte[]> commitmentSecrets = new ArrayList<>();
        ArrayList<byte[]> commitments = new ArrayList<>();
        ArrayList<byte[]> challenges = new ArrayList<>();
        ArrayList<byte[]> signatureShares = new ArrayList<>();
        byte[] aggregatedCommitment;
        byte[] aggregatedSignature = new byte[0];
        int sizeConsensusGroup = 1;
        MultiSignatureService multiSignatureService = AppServiceProvider.getMultiSignatureService();

        byte[][] result = new byte[2][];

        for (int i = 0; i < sizeConsensusGroup; i++) {
            signersPublicKeys.add(new PublicKey(privateKey).getValue());
            commitmentSecrets.add(multiSignatureService.computeCommitmentSecret());
            commitments.add(multiSignatureService.computeCommitment(commitmentSecrets.get(i)));
        }

        byte[] blockHashNoSig = AppServiceProvider.getSerializationService().getHash(block);
        logger.trace("done hashing block {}", blockHashNoSig);

        aggregatedCommitment = multiSignatureService.aggregateCommitments(commitments, 1);
        logger.trace("done aggregating commitments {}", aggregatedCommitment);

        // compute challenges and signatures for each signer
        for (int i = 0; i < sizeConsensusGroup; i++) {
            if (0 != ((1 << i) & 1)) {
                challenges.add(
                        multiSignatureService.computeChallenge(
                                signersPublicKeys,
                                signersPublicKeys.get(i),
                                aggregatedCommitment,
                                blockHashNoSig,
                                1
                        )
                );

                // compute signature shares
                signatureShares.add(
                        multiSignatureService.computeSignatureShare(
                                challenges.get(i),
                                privateKey.getValue(),
                                commitmentSecrets.get(i)
                        )
                );
            } else {
                challenges.add(new byte[0]);
                signatureShares.add(new byte[0]);
            }

            aggregatedSignature = multiSignatureService.aggregateSignatures(signatureShares, 1);
        }
        logger.trace("done aggregating signature {}", aggregatedSignature);

        //boolean sigOk = multiSignatureService.verifyAggregatedSignature(signersPublicKeys,aggregatedSignature, aggregatedCommitment, blockHashNoSig, 1 );
        block.setSignature(aggregatedSignature);
        block.setCommitment(aggregatedCommitment);
        logger.trace("placed signature data on block!");
        logger.traceExit();
    }

    public void removeAlreadyProcessedTransactionsFromPool(AppState state, Block block){
        Util.check(state != null, "state != null");
        Util.check(block != null, "block != null");

        List<String> toBeRemoved = BlockUtil.getTransactionsHashesAsString(block);

        ArrayBlockingQueue<String> transactionPool = state.getTransactionPool();
        transactionPool.removeAll(toBeRemoved);

        logger.debug("Removed {} transactions from pool, remaining: {}", toBeRemoved.size(),  transactionPool.size());

    }
}
