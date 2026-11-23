package com.beaconculinary.api.bar;

import com.beaconculinary.api.common.SseChannel;
import com.beaconculinary.api.common.SseKeepalive;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The bar display's own SSE channel, separate from the kitchen's so neither ever receives the
 * other's view of an order. */
@Component
public class BarEventBroadcaster {
    // The open streams; they open with ":connected" and get the keepalive (common.SseChannel, SseKeepalive).
    private final SseChannel channel;

    public BarEventBroadcaster(SseKeepalive keepalive) {
        this.channel = keepalive.channel("bar");
    }

    public SseEmitter subscribe() {
        return channel.subscribe();
    }

    public void broadcast(Object payload) {
        channel.broadcast(payload);
    }
}
