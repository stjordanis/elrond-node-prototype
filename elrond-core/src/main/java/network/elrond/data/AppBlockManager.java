package network.elrond.data;

import javafx.util.Pair;
import network.elrond.TimeWatch;
import network.elrond.account.Accounts;
import network.elrond.application.AppState;
import network.elrond.benchmark.Statistic;
import network.elrond.blockchain.Blockchain;
import network.elrond.blockchain.BlockchainService;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.chronology.ChronologyService;
import network.elrond.chronology.NTPClient;
import network.elrond.chronology.Round;
import network.elrond.core.Util;
import network.elrond.crypto.MultiSignatureService;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.p2p.P2PBroadcastChanel;
import network.elrond.p2p.P2PChannelName;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

//TODO: remove from "data" package
public class AppBlockManager {
    private static final Logger logger = LogManager.getLogger(AppBlockManager.class);

    private static AppBlockManager instance = new AppBlockManager();

    public static AppBlockManager instance() {
        return instance;
    }

    public Block generateAndBroadcastBlock(ArrayBlockingQueue<String> queue, PrivateKey privateKey, AppState state) {
        logger.traceEntry("params: {} {} {}", queue, privateKey, state);
        Accounts accounts = state.getAccounts();
        Blockchain blockchain = state.getBlockchain();

        List<String> hashes = new ArrayList<>(queue);

        if (hashes.isEmpty()) {
            logger.info("Can't execute, no transaction!");
            AppServiceProvider.getStatisticService().addStatistic(new Statistic(0));
            return null;
        }

        BlockchainService blockchainService = AppServiceProvider.getBlockchainService();
        try {
            List<Transaction> transactions = blockchainService.getAll(hashes, blockchain, BlockchainUnitType.TRANSACTION);
            Pair<Block, List<Receipt>> blockReceiptsPair = composeBlock(transactions, state);
            Block block = blockReceiptsPair.getKey();
            List<Receipt> receipts = blockReceiptsPair.getValue();

            AppBlockManager.instance().signBlock(block, privateKey);
            ExecutionService executionService = AppServiceProvider.getExecutionService();
            ExecutionReport result = executionService.processBlock(block, accounts, blockchain);

            if (result.isOk()) {
                String hashBlock = AppServiceProvider.getSerializationService().getHashString(block);
                AppServiceProvider.getBootstrapService().commitBlock(block, hashBlock, blockchain);

                List<String> txHashes  = new ArrayList<>();
                for (Receipt receipt : receipts) {
                    // add the blockHash to the receipt as the valid hash is only available after signing
                    receipt.setBlockHash(hashBlock);
                    sendReceipt(block, receipt, state);
                    txHashes.add(receipt.getTransactionHash());
                }

                queue.removeAll(txHashes);

                logger.info("New block proposed with hash {}", hashBlock);
                return logger.traceExit(block);
            }
        } catch (IOException | ClassNotFoundException e) {
            logger.throwing(e);
        }

        return logger.traceExit((Block) null);
    }


    public Pair<Block, List<Receipt>> composeBlock(List<Transaction> transactions, AppState state) throws IOException {
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
        Round round = chronologyService.getRoundFromDateTime(blockchain.getGenesisBlock().getTimestamp(),
                chronologyService.getSynchronizedTime(ntpClient));
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

    private List<Receipt> addTransactions(List<Transaction> transactions, Block block, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", transactions, block, state);
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
            block.getListTXHashes().add(txHash);

            if(tw.time(TimeUnit.MILLISECONDS) > 1000){
                break;
            }
        }

        return logger.traceExit(receipts);
    }

    private Receipt acceptTransaction(Block block, Transaction transaction, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, transaction, state);
        ReceiptStatus status = ReceiptStatus.ACCEPTED;
        String log = "Transaction processed";
        String transactionHash = AppServiceProvider.getSerializationService().getHashString(transaction);
        Receipt receipt = new Receipt(null, transactionHash, status, log);

        return logger.traceExit(receipt);
    }

    private Receipt rejectTransaction(Block block, Transaction transaction, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, transaction, state);
        ReceiptStatus status = ReceiptStatus.REJECTED;
        String log = "Invalid transaction";
        String transactionHash = AppServiceProvider.getSerializationService().getHashString(transaction);
        Receipt receipt = new Receipt(null, transactionHash, status, log);

        return logger.traceExit(receipt);
    }

    private void sendReceipt(Block block, Receipt receipt, AppState state) throws IOException {
        logger.traceEntry("params: {} {} {}", block, receipt, state);
        String blockHash = AppServiceProvider.getSerializationService().getHashString(block);
        String receiptHash = AppServiceProvider.getSerializationService().getHashString(receipt);
        String transactionHash = receipt.getTransactionHash();

        // Store on blockchain
        Blockchain blockchain = state.getBlockchain();
        AppServiceProvider.getBlockchainService().put(transactionHash, receiptHash, blockchain, BlockchainUnitType.TRANSACTION_RECEIPT);
        AppServiceProvider.getBlockchainService().put(transactionHash, blockHash, blockchain, BlockchainUnitType.TRANSACTION_BLOCK);
        AppServiceProvider.getBlockchainService().put(receiptHash, receipt, blockchain, BlockchainUnitType.RECEIPT);
        logger.trace("placed on blockchain (TRANSACTION_RECEIPT, TRANSACTION_BLOCK and RECEIPT)");

        // Broadcast
        P2PBroadcastChanel channel = state.getChanel(P2PChannelName.RECEIPT);
        AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, receiptHash);
        logger.trace("broadcast the receipt hash");
        logger.traceExit();
    }

    private Block getNewBlockAndBindToPrevious(Block currentBlock) {
        logger.traceEntry("params: {}", currentBlock);
        Block block = new Block();
        byte[] hash = AppServiceProvider.getSerializationService().getHash(currentBlock);

        // Bind on prev block
        block.setPrevBlockHash(hash);
        BigInteger nonce = currentBlock.getNonce().add(BigInteger.ONE);
        block.setNonce(nonce);
        return logger.traceExit(block);
    }

    public void signBlock(Block block, PrivateKey privateKey) {
        logger.traceEntry("params: {} {}", block, privateKey);
        Util.check(block != null, "block != null");
        Util.check(privateKey != null, "application != null");

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
}
