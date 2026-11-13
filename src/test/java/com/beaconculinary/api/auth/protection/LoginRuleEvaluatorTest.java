package com.beaconculinary.api.auth.protection;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** D3 thresholds with the default settings: the 6th attempt in a window is the first that would be blocked. */
class LoginRuleEvaluatorTest {
    private final LoginProtectionProperties properties = new LoginProtectionProperties();

    @Test
    void password_fiveFailuresInWindow_blocksTheSixthAttempt() {
        assertThat(evaluate(LoginMethod.PASSWORD, 4, 0).blocking()).isNull();
        assertThat(evaluate(LoginMethod.PASSWORD, 5, 0).blocking()).isEqualTo(LoginRule.PASSWORD_ACCOUNT);
    }

    @Test
    void pin_pairRule_atFive() {
        assertThat(evaluate(LoginMethod.PIN, 4, 4).blocking()).isNull();
        var evaluation = evaluate(LoginMethod.PIN, 5, 5);
        assertThat(evaluation.blocking()).isEqualTo(LoginRule.PIN_PAIR);
        assertThat(evaluation.alert()).isNull();
    }

    @Test
    void pin_accountCeiling_atTwenty_winsOverPairAndAlerts() {
        assertThat(evaluate(LoginMethod.PIN, 0, 19).blocking()).isNull();
        var evaluation = evaluate(LoginMethod.PIN, 5, 20);
        assertThat(evaluation.blocking()).isEqualTo(LoginRule.PIN_ACCOUNT_CEILING);
        assertThat(evaluation.alert()).isEqualTo(LoginRule.PIN_ACCOUNT_CEILING);
    }

    @Test
    void managerPin_callerBlocks_globalOnlyAlerts() {
        assertThat(evaluate(LoginMethod.MANAGER_PIN, 5, 5).blocking()).isEqualTo(LoginRule.MANAGER_PIN_CALLER);
        var globalOnly = evaluate(LoginMethod.MANAGER_PIN, 2, 20);
        assertThat(globalOnly.blocking()).isNull();
        assertThat(globalOnly.alert()).isEqualTo(LoginRule.MANAGER_PIN_GLOBAL);
        var both = evaluate(LoginMethod.MANAGER_PIN, 5, 20);
        assertThat(both.blocking()).isEqualTo(LoginRule.MANAGER_PIN_CALLER);
        assertThat(both.alert()).isEqualTo(LoginRule.MANAGER_PIN_GLOBAL);
    }

    @Test
    void changePassword_atFive() {
        assertThat(evaluate(LoginMethod.CHANGE_PASSWORD, 4, 0).blocking()).isNull();
        assertThat(evaluate(LoginMethod.CHANGE_PASSWORD, 5, 0).blocking()).isEqualTo(LoginRule.CHANGE_PASSWORD_USER);
    }

    @Test
    void thresholdsAreConfigurable() {
        properties.getPasswordAccount().setMaxFailures(3);
        assertThat(evaluate(LoginMethod.PASSWORD, 3, 0).blocking()).isEqualTo(LoginRule.PASSWORD_ACCOUNT);
    }

    @Test
    void lockEscalation_fifteenMinutes_thenOneHour_thenAdminUnlock() {
        assertThat(LoginRuleEvaluator.nextLockLevel(0)).isEqualTo(1);
        assertThat(LoginRuleEvaluator.nextLockLevel(1)).isEqualTo(2);
        assertThat(LoginRuleEvaluator.nextLockLevel(2)).isEqualTo(3);
        assertThat(LoginRuleEvaluator.nextLockLevel(7)).isEqualTo(3);
        assertThat(LoginRuleEvaluator.lockDuration(1, properties)).isEqualTo(Duration.ofMinutes(15));
        assertThat(LoginRuleEvaluator.lockDuration(2, properties)).isEqualTo(Duration.ofMinutes(60));
        assertThat(LoginRuleEvaluator.lockDuration(3, properties)).isNull();
    }

    @Test
    void ruleEffects() {
        assertThat(LoginRule.PASSWORD_ACCOUNT.effect()).isEqualTo(LoginRule.Effect.LOCK);
        assertThat(LoginRule.PIN_ACCOUNT_CEILING.effect()).isEqualTo(LoginRule.Effect.LOCK);
        assertThat(LoginRule.PIN_PAIR.effect()).isEqualTo(LoginRule.Effect.BLOCK);
        assertThat(LoginRule.MANAGER_PIN_GLOBAL.effect()).isEqualTo(LoginRule.Effect.ALERT);
    }

    @Test
    void failureOutcomes() {
        assertThat(LoginOutcome.BAD_CREDENTIAL.isFailure()).isTrue();
        assertThat(LoginOutcome.UNKNOWN_USER.isFailure()).isTrue();
        assertThat(LoginOutcome.DISABLED.isFailure()).isTrue();
        assertThat(LoginOutcome.VALIDATION.isFailure()).isFalse();
        assertThat(LoginOutcome.SUCCESS.isFailure()).isFalse();
    }

    private LoginRuleEvaluator.Evaluation evaluate(LoginMethod method, long primary, long secondary) {
        return LoginRuleEvaluator.evaluate(method, primary, secondary, properties);
    }
}
