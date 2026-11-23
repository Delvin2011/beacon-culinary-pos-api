package com.beaconculinary.api.common;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * The SSE streams (kitchen, bar, public board) start here. A new stream sends one comment line (":connected") at
 * once: Spring sends the response headers only with the first write, so without it a stream stayed silent until
 * the first order event. Then a screen didn't show as connected, and a gateway that waits for the first byte (Front
 * Door's origin response timeout) closed quiet streams. Clients ignore comment lines (the SSE format; the
 * frontend's parser reads only "data:" lines).
 */
public final class SseStreams {
    /** The comment sent when a stream opens. */
    public static final String CONNECTED_COMMENT = "connected";

    private SseStreams() {
    }

    /** A stream with no timeout that has already queued its opening comment. */
    public static SseEmitter open() {
        var emitter = new SseEmitter(0L);
        try {
            // Queued until Spring starts the response, then written and flushed with the headers.
            emitter.send(SseEmitter.event().comment(CONNECTED_COMMENT));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
