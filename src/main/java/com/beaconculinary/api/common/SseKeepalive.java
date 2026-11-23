package com.beaconculinary.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps quiet SSE streams open through gateways that close idle connections (Azure Front Door: about 4 minutes):
 * every {@code app.sse.keepalive-interval} (default 25 s; 0 turns it off) each open stream gets an SSE comment,
 * ":keepalive". Clients ignore comments (the SSE format; the frontend's parser reads only "data:" lines).
 *
 * <p>It isn't activity: the server writes into a response that is already open, so no request is made and nothing
 * that counts activity runs (a POS session is touched only by a till's own write request, a device's last-seen by
 * the request filter). Tills don't open streams at all; only the kitchen, bar and board screens do.
 */
@Component
public class SseKeepalive implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(SseKeepalive.class);

    private final List<SseChannel> channels = new CopyOnWriteArrayList<>();
    private final Duration interval;
    private final ScheduledFuture<?> task;

    public SseKeepalive(TaskScheduler scheduler, @Value("${app.sse.keepalive-interval:25s}") Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("app.sse.keepalive-interval must be 0 (off) or a positive duration, e.g. 25s");
        }
        this.interval = interval;
        this.task = interval.isZero() ? null
                : scheduler.scheduleAtFixedRate(this::sendKeepalives, Instant.now().plus(interval), interval);
    }

    /** A new channel whose open streams get the keepalive. One per kind of stream, made by its broadcaster. */
    public SseChannel channel(String name) {
        var channel = new SseChannel(name);
        channels.add(channel);
        return channel;
    }

    /** Sends one round of keepalives; returns how many streams received it. Never throws (the schedule must go on). */
    int sendKeepalives() {
        var sent = 0;
        for (var channel : channels) {
            try {
                sent += channel.keepalive();
            } catch (RuntimeException e) {
                log.warn("SSE keepalive failed on the {} channel", channel.name(), e);
            }
        }
        return sent;
    }

    public Duration interval() {
        return interval;
    }

    /** True when keepalives are scheduled (the interval isn't 0). */
    public boolean enabled() {
        return task != null;
    }

    /** How many streams are open across every channel. */
    public int openStreams() {
        return channels.stream().mapToInt(SseChannel::openStreams).sum();
    }

    @Override
    public void destroy() {
        if (task != null) {
            task.cancel(false);
        }
    }
}
