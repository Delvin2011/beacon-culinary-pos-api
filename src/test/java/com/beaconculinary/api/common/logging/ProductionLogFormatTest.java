package com.beaconculinary.api.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-1: production logs are Logstash JSON, one object per line. Uses Boot's own encoder in a
 * private logback context, so the shared test logging (text) is never switched to JSON.
 */
class ProductionLogFormatTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theProdProfile_selectsLogstashJsonOnTheConsole() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yaml"));
        assertThat(yaml.getObject()).containsEntry("logging.structured.format.console", "logstash");
    }

    @Test
    void aRequestLine_isOneJsonObject_withTheMdcFieldsKeyValuesAndTags() throws Exception {
        var encoder = encoder();
        var context = (LoggerContext) encoder.getContext();
        var event = new LoggingEvent(getClass().getName(), context.getLogger(RequestContextFilter.class), Level.INFO,
                "request {} {} status={} durationMs={}", null,
                new Object[]{"POST", "/orders", 201, 42});
        event.setMDCPropertyMap(Map.of("requestId", "req-12345678", "tenantId", "default", "userId", "7",
                "role", "CASHIER", "sessionId", "s-1", "deviceId", "d-1", "tillCode", "T1", "method", "POST",
                "path", "/orders", "route", "/orders"));
        event.addKeyValuePair(new org.slf4j.event.KeyValuePair("status", 201));
        event.addKeyValuePair(new org.slf4j.event.KeyValuePair("durationMs", 42));
        event.addMarker(LogMarkers.LOGIN_PROTECTION_ALERT);

        var line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line).endsWith("\n");
        assertThat(line.strip()).doesNotContain("\n");
        JsonNode json = MAPPER.readTree(line);
        assertThat(json.get("@timestamp")).isNotNull();
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("logger_name").asText()).isEqualTo(RequestContextFilter.class.getName());
        assertThat(json.get("message").asText()).isEqualTo("request POST /orders status=201 durationMs=42");
        assertThat(json.get("requestId").asText()).isEqualTo("req-12345678");
        assertThat(json.get("tenantId").asText()).isEqualTo("default");
        assertThat(json.get("userId").asText()).isEqualTo("7");
        assertThat(json.get("tillCode").asText()).isEqualTo("T1");
        assertThat(json.get("route").asText()).isEqualTo("/orders");
        assertThat(json.get("status").asInt()).isEqualTo(201);
        assertThat(json.get("durationMs").asInt()).isEqualTo(42);
        assertThat(json.get("tags").toString()).contains("LOGIN_PROTECTION_ALERT");
        encoder.stop();
    }

    @Test
    void anExceptionStaysOnOneLine_asAStackTraceField() throws Exception {
        var encoder = encoder();
        var context = (LoggerContext) encoder.getContext();
        var event = new LoggingEvent(getClass().getName(), context.getLogger("test"), Level.ERROR,
                "job demo failed", new IllegalStateException("boom"), null);
        // A bare logback context has no MDC adapter; give the event its MDC directly.
        event.setMDCPropertyMap(Map.of("jobName", "demo", "runId", "0a1b2c3d"));

        var line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line.strip()).doesNotContain("\n");
        var json = MAPPER.readTree(line);
        assertThat(json.get("stack_trace").asText()).contains("IllegalStateException: boom");
        encoder.stop();
    }

    private static StructuredLogEncoder encoder() {
        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), new MockEnvironment());
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();
        return encoder;
    }
}
