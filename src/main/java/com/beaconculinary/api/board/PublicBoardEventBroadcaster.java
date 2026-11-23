package com.beaconculinary.api.board;

import com.beaconculinary.api.common.SseChannel;
import com.beaconculinary.api.common.SseKeepalive;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The public board's own SSE channel — a separate emitter registry from the kitchen's, so a filtered public payload is never at risk of leaking into the kitchen stream or vice versa. Both are driven from the same {@code orders.OrderStatusEventPublisher} call site, so they can't drift out of sync despite being separate channels. */
@Component
public class PublicBoardEventBroadcaster {
    // The open streams; they open with ":connected" and get the keepalive (common.SseChannel, SseKeepalive).
    private final SseChannel channel;

    public PublicBoardEventBroadcaster(SseKeepalive keepalive) {
        this.channel = keepalive.channel("board");
    }

    public SseEmitter subscribe() {
        return channel.subscribe();
    }

    public void broadcast(Object payload) {
        channel.broadcast(payload);
    }
}
