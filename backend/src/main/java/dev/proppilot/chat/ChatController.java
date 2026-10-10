package dev.proppilot.chat;

import dev.proppilot.actions.ActionException;
import dev.proppilot.actions.VisitorActors;
import dev.proppilot.agent.ChatTurn;
import dev.proppilot.agent.DecisionNotice;
import dev.proppilot.config.ChatProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final int MAX_HISTORY_TURN_LENGTH = 4000;

    private final ChatService chat;
    private final ChatRateLimiter limiter;
    private final ChatProperties props;
    private final VisitorActors visitors;

    public ChatController(ChatService chat, ChatRateLimiter limiter, ChatProperties props, VisitorActors visitors) {
        this.chat = chat;
        this.limiter = limiter;
        this.props = props;
        this.visitors = visitors;
    }

    @PostMapping
    public SseEmitter chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        validate(request);
        switch (limiter.tryAcquire(http.getRemoteAddr())) {
            case RATE_LIMITED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many questions. Please wait a minute and try again.");
            case DAILY_LIMIT_REACHED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "The daily question limit of this demo has been reached. Please come back tomorrow.");
            case ALLOWED -> { }
        }
        try {
            return chat.start(request, visitors.actorFor(http.getRemoteAddr()));
        } catch (ActionException refused) {
            throw new ResponseStatusException(refused.error().status(), refused.getMessage());
        }
    }

    private void validate(ChatRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        // With an actionId the server writes the turn and the message is not read.
        if (request.actionId() == null) {
            validateMessage(request.message());
        }
        var history = request.historyOrEmpty();
        if (history.size() > props.maxHistoryItems()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "history must have at most " + props.maxHistoryItems() + " items");
        }
        for (ChatTurn turn : history) {
            if (turn == null || turn.text() == null || turn.role() == null || turn.text().length() > MAX_HISTORY_TURN_LENGTH
                    || (turn.fromUser() && usesReservedMarker(turn.text()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid history");
            }
        }
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (message.length() > props.maxMessageLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message must be at most " + props.maxMessageLength() + " characters");
        }
        if (usesReservedMarker(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message must not contain " + DecisionNotice.MARKER);
        }
    }

    /** Only the server writes a decision notice, so a visitor's text cannot pose as one. */
    private static boolean usesReservedMarker(String text) {
        return text.contains(DecisionNotice.MARKER);
    }
}
