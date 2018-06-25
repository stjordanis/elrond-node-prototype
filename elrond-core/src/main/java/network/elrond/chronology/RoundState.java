package network.elrond.chronology;

import network.elrond.consensus.EventHandler_ASSEMBLY_BLOCK;
import network.elrond.consensus.EventHandler_COMPUTE_LEADER;
import network.elrond.consensus.EventHandler_START_ROUND;
import network.elrond.core.EventHandler;

import java.util.*;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public enum RoundState {
//    START_ROUND(0, new SubRoundEventHandler()),
//    PROPOSE_BLOCK(2000, null),
//    VERIFY_BLOCK(1000, null),
//    MULTI_SIGN_ROUND_1(250, new SubRoundEventHandler()),
//    MULTI_SIGN_ROUND_2(250, null),
//    MULTI_SIGN_ROUND_3(250, null),
//    END_ROUND(0, new SubRoundEventHandler());

    START_ROUND(0, new EventHandler_START_ROUND()),
    COMPUTE_LEADER(200, new EventHandler_COMPUTE_LEADER()),
    PROPOSE_BLOCK(2000, new EventHandler_ASSEMBLY_BLOCK()),
    FINISH_PROPOSE_BLOCK(200, null),
    BROADCAST_BLOCK(1800, null),
    END_ROUND(0, null);

    private final int roundStateDuration;
    private final EventHandler eventHandler;

    private final static EnumSet<RoundState> MAIN_SET = EnumSet.allOf(RoundState.class);

    RoundState(final int roundStateDuration, final EventHandler eventHandler) {
        this.roundStateDuration = roundStateDuration;
        this.eventHandler = eventHandler;
    }

    public int getRoundStateDuration(){
        return (roundStateDuration);
    }

    public EventHandler getEventHandler() {
        return (eventHandler);
    }

    @Override
    public String toString(){
        return (String.format("SubRoundType{%s, order=%d, duration:%d}", this.name(), this.ordinal(), this.roundStateDuration));
    }

    public static EnumSet<RoundState> getEnumSet(){
        return(MAIN_SET);
    }
}
