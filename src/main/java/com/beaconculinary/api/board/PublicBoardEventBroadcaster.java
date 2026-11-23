package com.beaconculinary.api.board;

import com.beaconculinary.api.common.SseStreams;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The public board's own SSE channel — a separate emitter registry from the kitchen's, so a filtered public payload is never at risk of leaking into the kitchen stream or vice versa. Both are driven from the same {@code orders.OrderStatusEventPublisher} call site, so they can't drift out of sync despite being separate channels. */
@Component
public class PublicBoardEventBroadcaster {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        // Opens with a comment, so the headers go out at once (common.SseStreams).
        var emitter = SseStreams.open();
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(Object payload) {
        for (var emitter : emitters) {
            try {
                emitter.send(payload);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }
}
