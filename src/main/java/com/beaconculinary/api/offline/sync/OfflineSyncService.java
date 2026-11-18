package com.beaconculinary.api.offline.sync;

import com.beaconculinary.api.devices.DeviceContext;
import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.offline.OfflineException;
import com.beaconculinary.api.offline.OfflineGrantSigner;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.Sale;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SaleResult;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SyncRequest;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SyncResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * O4: POST /pos/offline/sales (contract sections 2 to 4). Authenticates the till by its cookie (a
 * revoked or expired one too, here only), checks each grant's signature and till, then records each
 * sale in its own transaction, in order. One sale's problem never stops the rest; an unexpected
 * server error ends the batch with a 500, and the till resends it (what was recorded comes back as
 * DUPLICATE).
 */
@Service
public class OfflineSyncService {
    static final int MAX_SALES = 50;
    static final int MAX_GRANTS = 10;
    private static final Set<DeviceContext.Status> ACCEPTED = EnumSet.of(
            DeviceContext.Status.VALID, DeviceContext.Status.REVOKED, DeviceContext.Status.EXPIRED);

    private final OfflineGrantSigner signer;
    private final OfflineGrantStore grants;
    private final OfflineSaleRecorder recorder;
    private final TransactionTemplate perSale;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OfflineSyncService(OfflineGrantSigner signer, OfflineGrantStore grants, OfflineSaleRecorder recorder,
                              PlatformTransactionManager transactionManager, ObjectMapper objectMapper, Clock clock) {
        this.signer = signer;
        this.grants = grants;
        this.recorder = recorder;
        this.perSale = new TransactionTemplate(transactionManager);
        this.perSale.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SyncResponse sync(SyncRequest request) {
        var device = DeviceContext.current();
        if (!ACCEPTED.contains(device.status()) || device.deviceId() == null) {
            throw DeviceException.notRegistered();
        }
        if (request == null || request.grants() == null || request.grants().isEmpty() || request.grants().size() > MAX_GRANTS
                || request.sales() == null || request.sales().isEmpty() || request.sales().size() > MAX_SALES) {
            throw new OfflineException(HttpStatus.BAD_REQUEST, "INVALID_BATCH",
                    "A batch needs 1 to " + MAX_GRANTS + " grants and 1 to " + MAX_SALES + " sales.", null);
        }
        var deviceId = UUID.fromString(device.deviceId());
        var context = new OfflineSaleRecorder.Context(deviceId, device.status().name(), clock.instant());

        var usable = new HashMap<UUID, OfflineGrantStore.GrantRow>();
        var refused = new HashSet<UUID>();
        sortGrants(request, deviceId, usable, refused);

        var results = new ArrayList<SaleResult>();
        for (var node : request.sales()) {
            results.add(one(node, usable, refused, context));
        }
        return new SyncResponse(results, clock.instant());
    }

    private SaleResult one(JsonNode node, Map<UUID, OfflineGrantStore.GrantRow> usable, Set<UUID> refused,
                           OfflineSaleRecorder.Context context) {
        Sale sale;
        try {
            sale = objectMapper.treeToValue(node, Sale.class);
        } catch (Exception e) {
            return SaleResult.invalid(text(node, "clientOrderId"), "The sale couldn't be read.");
        }
        if (sale == null) {
            return SaleResult.invalid(null, "The sale couldn't be read.");
        }
        var clientOrderId = sale.clientOrderId() == null ? text(node, "clientOrderId") : sale.clientOrderId().toString();
        var problem = OfflineSaleValidation.problem(sale);
        if (problem != null) {
            return SaleResult.invalid(clientOrderId, problem);
        }
        var grant = usable.get(sale.grantId());
        if (grant == null) {
            return refused.contains(sale.grantId())
                    ? SaleResult.refused(clientOrderId, "The grant for this sale isn't valid.")
                    : SaleResult.invalid(clientOrderId, "grantId has no matching grant in this batch.");
        }
        try {
            return perSale.execute(status -> recorder.record(sale, grant, context));
        } catch (DataIntegrityViolationException raced) {
            // The same clientOrderId recorded by a concurrent request: read it back as a duplicate.
            return perSale.execute(status -> recorder.record(sale, grant, context));
        }
    }

    /** Usable: a genuine grant of this till that the server issued. Refused: anything else we can name. */
    private void sortGrants(SyncRequest request, UUID deviceId, Map<UUID, OfflineGrantStore.GrantRow> usable,
                            Set<UUID> refused) {
        for (var token : request.grants()) {
            var verified = signer.verify(token);
            if (verified.isEmpty()) {
                var claimed = unverifiedGrantId(token);
                if (claimed != null) {
                    refused.add(claimed);
                }
                continue;
            }
            var claims = verified.get().claims();
            var row = grants.find(claims.grantId());
            if (!claims.deviceId().equals(deviceId) || row.isEmpty() || !row.get().deviceId().equals(deviceId)) {
                refused.add(claims.grantId());
            } else {
                usable.put(claims.grantId(), row.get());
            }
        }
    }

    /** The gid a token claims, read without trusting it: only to answer REFUSED rather than INVALID. */
    private UUID unverifiedGrantId(String token) {
        try {
            var parts = token.split("\\.");
            var payload = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return UUID.fromString(payload.get("gid").asText());
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
