package dev.proppilot.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The browser end of one chat run. Once a send fails the client counts as gone and later sends are skipped quietly, so
 * the agent can wind down and the run can still be recorded.
 */
final class ClientStream {

    private static final Logger log = LoggerFactory.getLogger(ClientStream.class);

    private final SseEmitter emitter;
    private final ObjectMapper json;
    private boolean gone;

    ClientStream(SseEmitter emitter, ObjectMapper json) {
        this.emitter = emitter;
        this.json = json;
    }

    boolean isGone() {
        return gone;
    }

    void send(String event, Object payload) {
        if (gone) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            gone = true;
            log.debug("Client disconnected mid-stream");
        }
    }

    void complete() {
        emitter.complete();
    }

    void completeWithError(Throwable error) {
        emitter.completeWithError(error);
    }
}
