package com.beaconculinary.api.tenancy.secrets;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * T1-1: secrets from the environment ({@link SecretNames#environmentVariable}): real environment variables, or the
 * .env file locally (devstack/stack.env reaches the backend container as environment variables). Each call reads the
 * value afresh and returns it; nothing is cached, logged or put in an exception. Replaced by a Key Vault resolver in
 * T1-0b behind {@link SecretResolver}.
 */
@Component
public class EnvironmentSecretResolver implements SecretResolver {
    private final Environment environment;

    public EnvironmentSecretResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolve(String secretName) {
        var variable = SecretNames.environmentVariable(secretName);
        var value = environment.getProperty(variable);
        if (value == null || value.isEmpty()) {
            throw new MissingSecretException(secretName, "environment variable " + variable);
        }
        return value;
    }
}
