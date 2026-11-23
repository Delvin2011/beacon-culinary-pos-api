package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.SseChannel;
import com.beaconculinary.api.common.SseKeepalive;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out for order lifecycle events over SSE. Deliberately generic — the kitchen stream
 * (Stage 2.1) is the first subscriber, but later stages (e.g. the public display) reuse the
 * same broadcaster instead of standing up a parallel push mechanism.
 */
@Component
public class OrderEventBroadcaster {
    // The open streams; they open with ":connected" and get the keepalive (common.SseChannel, SseKeepalive).
    private final SseChannel channel;

    public OrderEventBroadcaster(SseKeepalive keepalive) {
        this.channel = keepalive.channel("kitchen");
    }

    public SseEmitter subscribe() {
        return channel.subscribe();
    }

    public void broadcast(Object payload) {
        channel.broadcast(payload);
    }
}
