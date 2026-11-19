package com.beaconculinary.api.offline;

import com.beaconculinary.api.accounts.Account;
import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.inventory.VatSettings;
import com.beaconculinary.api.menu.DailyPlanningService;
import com.beaconculinary.api.menu.MealMapper;
import com.beaconculinary.api.menu.MealPeriod;
import com.beaconculinary.api.menu.MealPeriodRepository;
import com.beaconculinary.api.offline.OfflineDtos.Limits;
import com.beaconculinary.api.offline.OfflineDtos.Snapshot;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotAccount;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotContent;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotExtra;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotOption;
import com.beaconculinary.api.offline.OfflineDtos.SnapshotPeriod;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.VatRule;
import com.beaconculinary.api.receipts.ReceiptSettingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * O3: today's offline snapshot (contract section 8). The menu comes from
 * DailyPlanningService.dayMenu, the same listing rule, order and prices as GET /menu/today, but for
 * every period of the day and every status. snapshotVersion is a hash of everything except the two
 * timestamps, so unchanged data always gives the same ETag.
 */
@Service
public class OfflineSnapshotService {
    private static final int VERSION_BYTES = 16;

    private final OfflineProperties properties;
    private final DailyPlanningService dailyPlanningService;
    private final MealPeriodRepository mealPeriodRepository;
    private final MealMapper mealMapper;
    private final AccountRepository accountRepository;
    private final VatSettings vatSettings;
    private final ReceiptSettingsService receiptSettingsService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OfflineSnapshotService(OfflineProperties properties, DailyPlanningService dailyPlanningService,
                                  MealPeriodRepository mealPeriodRepository, MealMapper mealMapper,
                                  AccountRepository accountRepository, VatSettings vatSettings,
                                  ReceiptSettingsService receiptSettingsService, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.dailyPlanningService = dailyPlanningService;
        this.mealPeriodRepository = mealPeriodRepository;
        this.mealMapper = mealMapper;
        this.accountRepository = accountRepository;
        this.vatSettings = vatSettings;
        this.receiptSettingsService = receiptSettingsService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Snapshot build() {
        var now = clock.instant();
        var tradingDay = LocalDate.now(clock);
        var menu = dailyPlanningService.dayMenu(tradingDay);

        var periods = mealPeriodRepository.findAll().stream()
                .sorted(Comparator.comparing(MealPeriod::getStartTime, Comparator.nullsFirst(LocalTime::compareTo))
                        .thenComparing(MealPeriod::getId))
                .map(period -> new SnapshotPeriod(period.getId(), period.getName(), period.getStartTime(),
                        period.getEndTime(), period.isAllDay()))
                .toList();
        var options = menu.options().stream().map(option -> {
            var dto = mealMapper.toDto(option);
            return new SnapshotOption(dto.getId(), dto.getName(), option.getMealCatalog().getStation(), dto.getGroup(),
                    dto.getMealPeriodId(), dto.getPrice(), dto.getStatus(), dto.getPlannedPortions(),
                    dto.getPortionsRemaining(), dto.isOutToday(), option.isAlwaysOn());
        }).toList();
        var extras = menu.extras().stream().map(stock -> {
            var dto = mealMapper.toDto(stock);
            return new SnapshotExtra(dto.getId(), dto.getMealPeriodId(), dto.getComponentName(), dto.getExtraPrice(),
                    dto.getBufferRemaining());
        }).toList();

        var accountsEnabled = properties.isAccountsEnabled();
        var tenders = accountsEnabled ? List.of(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.ACCOUNT)
                : List.of(PaymentMethod.CASH, PaymentMethod.CARD);
        var accounts = accountsEnabled
                ? accountRepository.findByActive(true).stream()
                        .sorted(Comparator.comparing(Account::getAccountNumber, Comparator.nullsLast(String::compareTo))
                                .thenComparing(Account::getId))
                        .map(account -> new SnapshotAccount(account.getId(), account.getAccountNumber(), account.getName()))
                        .toList()
                : null;

        var content = new SnapshotContent(tradingDay, periods, options, extras,
                VatRule.storedRate(vatSettings.standardRate()), receiptSettingsService.forPos(), tenders,
                accountsEnabled, accountsEnabled ? properties.getAccountCap() : null, accounts,
                new Limits(properties.getMaxOfflineHours(), properties.getWarnOfflineHours()));
        return Snapshot.of(version(content), now, content);
    }

    private String version(SnapshotContent content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(content));
            return HexFormat.of().formatHex(digest, 0, VERSION_BYTES);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Couldn't compute the snapshot version", e);
        }
    }
}
