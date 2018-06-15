package network.elrond.chronology;

/*
This is a modified version of example by Jason Mathews, MITRE Corp that was
published on https://commons.apache.org/proper/commons-net/index.html
with the Apache Commons Net software.
 */
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import network.elrond.core.Util;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.NtpUtils;
import org.apache.commons.net.ntp.NtpV3Packet;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.commons.net.ntp.TimeStamp;

/**
 * NTPClient polls an NTP server with UDP  and returns milli seconds with
 * currentTimeMillis() intended as drop in replacement for System.currentTimeMillis()
 *
 * @author Will Shackleford
 */

public class NTPClient implements AutoCloseable{
    //final InetAddress hostAddr;
    NTPUDPClient ntpUdpClient;
    Thread pollThread = null;
    long pollMs;
    List<InetAddress> listHostsAddr = new ArrayList<>();
    List<String> listHosts = new ArrayList();
    int currentHost = 0;
    boolean offline = true;

    private void pollNtpServer() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(pollMs);
                    TimeInfo ti = ntpUdpClient.getTime(listHostsAddr.get(currentHost));
//                    long diff0 = ti.getMessage().getReceiveTimeStamp().getTime() - System.currentTimeMillis();
//                    System.out.println("diff0 = " + diff0);
                    this.setTimeInfo(ti);
                    offline = false;
                } catch (SocketTimeoutException ste) {
                    //try get another server from the list
                    currentHost++;
                    currentHost = currentHost % listHostsAddr.size();
                    offline = true;
                }
            }
        } catch (InterruptedException interruptedException) {
        } catch (IOException ex) {
            Logger.getLogger(NTPClient.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Connect to host and poll the host every poll_ms milliseconds.
     * Thread is started in the constructor.
     * @param listHosts
     * @param pollMs
     * @throws UnknownHostException
     * @throws SocketException
     */
    public NTPClient(List<String> listHosts, int pollMs) throws UnknownHostException, SocketException, NullPointerException {
        this.pollMs = pollMs;

        Util.check(listHosts != null, "listHosts should not be null!");

        StringBuilder stringBuilderHosts = new StringBuilder();

        //build internal lists
        for (int i = 0; i < listHosts.size(); i++){
            InetAddress host = InetAddress.getByName(listHosts.get(i));
            listHostsAddr.add(host);
            this.listHosts.add(listHosts.get(i));
            if (i > 0){
                stringBuilderHosts.append(", ");
            }
            stringBuilderHosts.append(host);
        }

        //if lists are empy, populate with default
        if (listHostsAddr.size() == 0){
            listHostsAddr.add(InetAddress.getByName("[N/A]"));
            listHosts.add("[N/A]");
        }

        ntpUdpClient = new NTPUDPClient();
        ntpUdpClient.setDefaultTimeout(10000);
        ntpUdpClient.open();
        ntpUdpClient.setSoTimeout(pollMs * 2 + 20);
        pollThread = new Thread(this::pollNtpServer, "pollNtpServer(" + stringBuilderHosts.toString() + "," + pollMs + ")");
        pollThread.start();
    }

    private TimeInfo timeInfo;
    private long timeInfoSetLocalTime;

    /**
     * Get the value of timeInfo
     *
     * @return the value of timeInfo
     */
    public synchronized TimeInfo getTimeInfo() {
        return timeInfo;
    }

    private synchronized void setTimeInfo(TimeInfo timeInfo) {
        this.timeInfo = timeInfo;
        timeInfoSetLocalTime = System.currentTimeMillis();
    }

    public long getPollMs(){
        return(pollMs);
    }

    public void setPollMs(int pollMs){
        try {
            ntpUdpClient.setSoTimeout(pollMs * 2 + 20);
            this.pollMs = pollMs;
        } catch (Exception ex){
            System.out.println("Error setting pollMs");
            ex.printStackTrace();
        }
    }

    /**
     * Returns milliseconds just as System.currentTimeMillis() but using the latest
     * estimate from the remote time server.
     * @return the difference, measured in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     */
    public long currentTimeMillis(){
        if (timeInfo == null){
            //if no message has been received, return current time
            return(System.currentTimeMillis());
        }

        long diff = System.currentTimeMillis() - timeInfoSetLocalTime;

        return timeInfo.getMessage().getReceiveTimeStamp().getTime() + diff;
    }

    public boolean isOffline(){
        return(offline);
    }

    public String getCurrentHostName(){
        return(listHosts.get(currentHost));
    }

    @Override
    public void close() throws Exception {
        if (null != pollThread) {
            pollThread.interrupt();
            pollThread.join(200);
            pollThread = null;
        }
        if (null != ntpUdpClient) {
            ntpUdpClient.close();
            ntpUdpClient = null;
        }

    }

    protected void finalizer() {
        try {
            this.close();
        } catch (Exception ex) {
            Logger.getLogger(NTPClient.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
