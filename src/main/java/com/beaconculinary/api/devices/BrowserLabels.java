package com.beaconculinary.api.devices;

/** A readable browser label from a User-Agent, such as "Edge on Windows". Admins can change it. */
final class BrowserLabels {
    private BrowserLabels() {
    }

    static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        var browser = userAgent.contains("Edg/") ? "Edge"
                : userAgent.contains("OPR/") ? "Opera"
                : userAgent.contains("Firefox/") ? "Firefox"
                : userAgent.contains("Chrome/") ? "Chrome"
                : userAgent.contains("Safari/") ? "Safari"
                : "Browser";
        var os = userAgent.contains("Windows") ? "Windows"
                : userAgent.contains("Android") ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS"
                : userAgent.contains("Mac OS") ? "macOS"
                : userAgent.contains("CrOS") ? "ChromeOS"
                : userAgent.contains("Linux") ? "Linux"
                : null;
        var label = os == null ? browser : browser + " on " + os;
        return label.length() <= 100 ? label : label.substring(0, 100);
    }
}
