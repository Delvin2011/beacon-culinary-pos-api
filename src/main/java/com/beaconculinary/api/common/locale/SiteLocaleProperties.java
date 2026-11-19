package com.beaconculinary.api.common.locale;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * T0-2 site locale ({@code site.*}): the business time zone and the currency. One site per
 * deployment for now; these are expected to become per-tenant settings later. Defaults are the
 * values the app has always used. {@link SiteLocale} checks them at startup (and
 * {@link SastViewGuard} checks the zone against the SQL report views).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "site")
public class SiteLocaleProperties {
    /** IANA zone id; the business day and every local time shown or exported are in it. */
    @NotBlank
    private String timeZone = "Africa/Johannesburg";

    /** ISO 4217 code, as reported in report metadata. */
    @NotBlank
    private String currencyCode = "ZAR";

    /** The symbol in money text ("R 1 234,56"). */
    @NotBlank
    private String currencySymbol = "R";
}
