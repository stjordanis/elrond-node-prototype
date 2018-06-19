package network.elrond.chronology;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChronologyServiceImpl implements ChronologyService {
    private final long roundsInEpochs;
    private final long roundTimeSeconds;

    private final List<String> listNTPServers = Arrays.asList("time.windows.com","time-a.nist.gov");
    private NTPClient ntpClient = null;

    public ChronologyServiceImpl(){
        roundsInEpochs = 28800;
        roundTimeSeconds = 4;
        try {
            ntpClient = new NTPClient(listNTPServers, 1000);
        } catch (Exception ex) {
            System.out.println("Error while instantiating ntpClient!");
            ex.printStackTrace();
        }
    }

    public ChronologyServiceImpl(long roundsInEpochs, long roundTimeSeconds){
        this.roundsInEpochs = roundsInEpochs;
        this.roundTimeSeconds = roundTimeSeconds;
        try {
            ntpClient = new NTPClient(listNTPServers, 1000);
        } catch (Exception ex) {
            System.out.println("Error while instantiating ntpClient!");
            ex.printStackTrace();
        }
    }

    public List<String> getListNTPServers(){
        return (listNTPServers);
    }

    public long getMillisecondsInEpoch(){
        return(roundsInEpochs * roundTimeSeconds * 100);
    }

    public boolean isDateTimeInEpoch(Epoch epoch, long dateMs) throws NullPointerException{
        if (epoch == null){
            throw new NullPointerException("epoch should not be null!");
        }

        return((epoch.getDateMsEpochStarts() <= dateMs) && (dateMs < epoch.getDateMsEpochStarts() + getMillisecondsInEpoch()));
    }

    public Round getRoundFromDateTime(Epoch epoch, long dateMs) throws NullPointerException, IllegalArgumentException{
        if (epoch == null){
            throw new NullPointerException("epoch should not be null!");
        }

        if (!isDateTimeInEpoch(epoch, dateMs)){
            throw new IllegalArgumentException(String.format("Parameter supplied %d does not belong to the supplied epoch [%d - %d)", dateMs,
                    epoch.getDateMsEpochStarts(), epoch.getDateMsEpochStarts() + getMillisecondsInEpoch()));
        }

        Round r = new Round();
        //(dateMs - epoch.dateMsEpochStarts) / roundTimeSeconds / 100;
        r.setRoundHeight((dateMs - epoch.getDateMsEpochStarts()) / roundTimeSeconds / 100);
        r.setLastRoundInEpoch(r.getRoundHeight() == (roundsInEpochs - 1));
        return(r);
    }

    public Epoch generateNewEpoch(Epoch previousEpoch) throws NullPointerException {
        if (previousEpoch == null){
            throw new NullPointerException("Parameter previousEpoch should not be null!");
        }

        Epoch newEpoch = new Epoch();
        newEpoch.setDateMsEpochStarts(previousEpoch.getDateMsEpochStarts() + getMillisecondsInEpoch());
        newEpoch.setEpochHeight(previousEpoch.getEpochHeight() + 1);
        //copy previous eligible list into the new epoch's eligible list
        synchronized (previousEpoch.listEligible){
            for (int i = 0; i < previousEpoch.listEligible.size(); i++) {
                newEpoch.listEligible.add(previousEpoch.listEligible.get(i));
            }
        }
        //copy previous waiting list into the new epoch's eligible list
        synchronized (previousEpoch.listWaiting){
            for (int i = 0; i < previousEpoch.listWaiting.size(); i++) {
                newEpoch.listEligible.add(previousEpoch.listWaiting.get(i));
            }
        }

        return(newEpoch);
    }

    public long getSynchronizedTime(){
        if (ntpClient != null){
            return(ntpClient.currentTimeMillis());
        }

        return(System.currentTimeMillis());
    }

    public NTPClient getNtpClient(){
        return(ntpClient);
    }

}