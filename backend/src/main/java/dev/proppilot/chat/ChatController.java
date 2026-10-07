package dev.proppilot.chat;

import dev.proppilot.agent.ChatTurn;
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

    public ChatController(ChatService chat, ChatRateLimiter limiter, ChatProperties props) {
        this.chat = chat;
        this.limiter = limiter;
        this.props = props;
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
        return chat.start(request);
    }

    private void validate(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (request.message().length() > props.maxMessageLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message must be at most " + props.maxMessageLength() + " characters");
        }
        for (ChatTurn turn : request.historyOrEmpty()) {
            if (turn == null || turn.text() == null || turn.role() == null || turn.text().length() > MAX_HISTORY_TURN_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid history");
            }
        }
    }
}
