package dev.devilswarchild.cinderlink.client;

/** Marks the connection attempt currently in progress as a CinderLink friend join (so its logging is scrubbed). */
public final class CinderJoin {
    /** Set by us right before calling ConnectScreen.startConnecting. */
    public static volatile boolean armed;
    /** True for the duration of the connection attempt that consumed {@link #armed}. */
    public static volatile boolean active;

    private CinderJoin() {}
}
