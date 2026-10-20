package dev.devilswarchild.cinderlink.core.net;

/** A UPnP failure. {@link #code} is the router's UPnP error code, or -1 for transport/parse problems. */
public class UpnpException extends Exception {
    public static final int CONFLICT_IN_MAPPING = 718;
    public static final int ONLY_PERMANENT_LEASES = 725;

    public final int code;

    public UpnpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public UpnpException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }
}
