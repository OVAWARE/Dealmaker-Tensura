package com.github.ovaware.dealmaker.deal;

public enum Party {
    DEALMAKER,
    ACCEPTOR,
    /** Valid only for event conditions such as chat, never as a transfer endpoint. */
    ANY_PLAYER
}
