package com.beaconculinary.api.orders;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out for order lifecycle events over SSE. Deliberately generic — the kitchen stream
 * (Stage 2.1) is the first subscriber, but later stages (e.g. the public display) reuse the
 * same broadcaster instead of standing up a parallel push mechanism.
 */
@Component
public class OrderEventBroadcaster {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L);
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
