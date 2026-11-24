package com.beaconculinary.api.common;

import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One kind of SSE stream (the kitchen's order stream, the bar's, the public board's): its open streams, opening a new
 * one, sending an event to all of them, and the keepalive comment ({@link SseKeepalive}). A stream leaves the channel
 * when it completes, times out or fails, and when a write to it fails (the client has gone), so a disconnected
 * screen gets nothing more. Channels come from {@link SseKeepalive#channel(String)}, which keeps them all alive.
 *
 * <p>T1-2: the open streams are kept per customer ({@link CustomerContext#scopeKey()}): a stream belongs to the
 * customer current when it was opened, and an event goes only to the current customer's streams. In single mode
 * there is one customer, so this is the old behaviour. The keepalive goes to every open stream.
 */
public final class SseChannel {
    private final String name;
    private final Map<String, List<SseEmitter>> emittersByCustomer = new ConcurrentHashMap<>();

    SseChannel(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** A new stream for the current customer that has already queued its opening comment (":connected"). */
    public SseEmitter subscribe() {
        var emitters = emittersByCustomer.computeIfAbsent(CustomerContext.scopeKey(), key -> new CopyOnWriteArrayList<>());
        var emitter = SseStreams.open();
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Sends an event (a "data:" line) to every open stream of the current customer; returns how many were sent. */
    public int broadcast(Object payload) {
        var emitters = emittersByCustomer.get(CustomerContext.scopeKey());
        if (emitters == null) {
            return 0;
        }
        var sent = 0;
        for (var emitter : emitters) {
            try {
                emitter.send(payload);
                sent++;
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
        return sent;
    }

    /**
     * Sends the keepalive comment (":keepalive", no "data:" line, so clients ignore it) to every open stream; returns
     * how many received it. A stream whose write fails is dropped and completed.
     */
    int keepalive() {
        var sent = 0;
        for (var emitters : emittersByCustomer.values()) {
            for (var emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment(SseStreams.KEEPALIVE_COMMENT));
                    sent++;
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
                    emitter.completeWithError(e);
                }
            }
        }
        return sent;
    }

    /** How many streams are open on this channel, for every customer. */
    public int openStreams() {
        return emittersByCustomer.values().stream().mapToInt(List::size).sum();
    }
}
