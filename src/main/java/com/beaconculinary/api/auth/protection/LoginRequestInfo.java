package com.beaconculinary.api.auth.protection;

/**
 * The network details recorded with an attempt (decision D7: raw values are kept so monitoring
 * shows what the Azure ingress sends). Values are truncated to their column sizes.
 */
public record LoginRequestInfo(String clientIp, String remoteAddr, String forwardedFor, String userAgent) {
    public static final LoginRequestInfo NONE = new LoginRequestInfo(null, null, null, null);

    static final int MAX_FORWARDED_FOR = 512;
    static final int MAX_USER_AGENT = 512;

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
