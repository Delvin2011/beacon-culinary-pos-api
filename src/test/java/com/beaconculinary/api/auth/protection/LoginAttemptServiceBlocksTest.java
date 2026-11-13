package com.beaconculinary.api.auth.protection;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Short-term blocks are listed only in enforce mode, and an error shows nothing rather than failing. */
class LoginAttemptServiceBlocksTest {
    private final LoginProtectionStore store = mock(LoginProtectionStore.class);

    private LoginAttemptService service(LoginProtectionMode mode) {
        var properties = new LoginProtectionProperties();
        properties.setMode(mode);
        return new LoginAttemptService(properties, store, new ClientIpResolver(List.of()), Clock.systemUTC());
    }

    @Test
    void monitorAndOff_listNoBlocks_andDoNotQuery() {
        assertThat(service(LoginProtectionMode.MONITOR).activeBlocks()).isEmpty();
        assertThat(service(LoginProtectionMode.OFF).activeBlocks()).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void enforce_onADatabaseError_listsNothing() {
        when(store.activeBlocks(any(), anyBoolean(), anyInt(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThat(service(LoginProtectionMode.ENFORCE).activeBlocks()).isEmpty();
    }
}
