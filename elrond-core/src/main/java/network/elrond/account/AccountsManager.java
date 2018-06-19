package network.elrond.account;

import network.elrond.core.Util;
import network.elrond.data.AppBlockManager;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.math.BigInteger;

public class AccountsManager {

    private static final Logger logger = LogManager.getLogger(AccountsManager.class);

    private static AccountsManager instance = new AccountsManager();

    public static AccountsManager instance() {
        return instance;
    }

    public Boolean hasFunds(Accounts accounts, String addressString, BigInteger value) throws IOException, ClassNotFoundException {
        logger.traceEntry("params: {} {} {}", accounts, addressString, value);
        Util.check(accounts != null, "accounts!=null");
        Util.check(!(addressString == null || addressString.isEmpty()), "addressString!=null");

        AccountAddress sendAddress = AccountAddress.fromHexString(addressString);
        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(sendAddress, accounts);

        return logger.traceExit(senderAccountState.getBalance().compareTo(value) >= 0);
    }

    public Boolean hasCorrectNonce(Accounts accounts, String addressString, BigInteger nonce) throws IOException, ClassNotFoundException {
        logger.traceEntry("params: {} {} {}", accounts, addressString, nonce);
        Util.check(accounts != null, "accounts!=null");
        Util.check(!(addressString == null || addressString.isEmpty()), "addressString!=null");

        return logger.traceExit(true);
        //TODO: uncomment in the future
//        AccountAddress sendAddress = AccountAddress.fromHexaString(addressString);
//        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(sendAddress, accounts);
//        return senderAccountState.getNonce().equals(nonce);
    }

    public void transferFunds(Accounts accounts, String senderAddressString, String receiverAddressString, BigInteger value, BigInteger nonce) throws IOException, ClassNotFoundException {
        logger.traceEntry("params: {} {} {} {} {}", accounts, senderAddressString, receiverAddressString, value, nonce);
        Util.check(accounts!=null, "accounts!=null");
        Util.check(!(senderAddressString == null || senderAddressString.isEmpty()), "senderAddressString!=null");
        Util.check(!(receiverAddressString == null || receiverAddressString.isEmpty()), "receiverAddressString!=null");
        Util.check(value.compareTo(BigInteger.ZERO) >= 0, "value>=0");
        Util.check(nonce.compareTo(BigInteger.ZERO) >= 0, "nonce>=0");

        if(!(hasFunds(accounts, senderAddressString, value) && hasCorrectNonce(accounts, senderAddressString, nonce))){
            IllegalArgumentException ex = new IllegalArgumentException("Validation of Sender Account failed!");
            logger.throwing(ex);
            throw ex;
        }

        logger.trace("Prefetching data...");
        AccountAddress senderAddress = AccountAddress.fromHexString(senderAddressString);
        AccountAddress receiverAddress = AccountAddress.fromHexString(receiverAddressString);
        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(senderAddress, accounts);
        AccountState receiverAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(receiverAddress, accounts);

        logger.trace("Transfer asset > adding");
        receiverAccountState.setBalance(receiverAccountState.getBalance().add(value));
        AppServiceProvider.getAccountStateService().setAccountState(receiverAddress, receiverAccountState, accounts); // PMS
        logger.trace("Transfer asset > substracting");
        senderAccountState.setBalance(senderAccountState.getBalance().subtract(value));
        //increase sender nonce
        logger.trace("Transfer asset > increasing sender nonce");
        senderAccountState.setNonce(senderAccountState.getNonce().add(BigInteger.ONE));
        logger.trace("Transfer asset > saving");
        AppServiceProvider.getAccountStateService().setAccountState(senderAddress, senderAccountState, accounts); // PMS

        logger.traceExit();
    }
}
