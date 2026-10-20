package dev.devilswarchild.cinderlink.core.presence;

/** What a friend's client reports. OFFLINE is never published; it's inferred from a stale record. */
public enum PresenceState {
    OFFLINE,
    ONLINE,
    HOSTING
}
