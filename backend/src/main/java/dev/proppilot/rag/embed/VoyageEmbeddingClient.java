package dev.proppilot.rag.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.agent.llm.ProviderErrors;
import dev.proppilot.agent.llm.RetryPolicy;
import dev.proppilot.config.EmbeddingProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the Voyage AI embeddings API over plain HTTP, retrying transient failures. Documents and questions are
 * embedded with their own {@code input_type}, which the model uses to put a question near the text that answers it.
 *
 * <p>A question waits for the answer only briefly, because someone is waiting for the reply it belongs to; a batch of
 * documents has longer, because it runs in the background. Neither the key nor a request body is ever logged or put
 * in an exception.
 */
public class VoyageEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);
    private static final String PROVIDER = "Voyage API";
    private static final Duration QUERY_BUDGET = Duration.ofSeconds(10);
    private static final Duration BATCH_BUDGET = Duration.ofSeconds(90);
    private static final int LOGGED_ERROR_BODY_CHARS = 300;

    private final EmbeddingProperties props;
    private final ObjectMapper json;
    private final RetryPolicy retries;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public VoyageEmbeddingClient(EmbeddingProperties props, ObjectMapper json) {
        this(props, json, RetryPolicy.withBackoff(props.maxRetries(), Duration.ofMillis(props.retryBaseDelayMs())));
    }

    VoyageEmbeddingClient(EmbeddingProperties props, ObjectMapper json, RetryPolicy retries) {
        if (props.apiKey().isEmpty()) {
            throw new IllegalStateException("VOYAGE_API_KEY is required when EMBEDDING_PROVIDER=voyage");
        }
        // The JDK quotes a header value it refuses, so a key like that would be written to the log in full.
        if (!props.apiKey().chars().allMatch(VoyageEmbeddingClient::isVisibleAscii)) {
            throw new IllegalStateException("VOYAGE_API_KEY may hold visible ASCII characters only: "
                    + "check it for a pasted quote, dash or line break");
        }
        this.props = props;
        this.json = json;
        this.retries = retries;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        var vectors = new ArrayList<float[]>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            var batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
            vectors.addAll(embed(batch, "document", BATCH_BUDGET));
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(text), "query", QUERY_BUDGET).get(0);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public int dimensions() {
        return props.dimensions();
    }

    /** One request, retried within {@code budget}: the time the whole call, retries and waits included, may take. */
    private List<float[]> embed(List<String> texts, String inputType, Duration budget) {
        long startedAt = System.nanoTime();
        Supplier<Duration> timeLeft = () -> budget.minus(Duration.ofNanos(System.nanoTime() - startedAt));
        return retries.execute(timeLeft, () -> attempt(texts, inputType, attemptTimeout(timeLeft.get())));
    }

    /** The configured limit, or less when the budget is nearly used up. */
    private Duration attemptTimeout(Duration timeLeft) {
        if (!timeLeft.isPositive()) {
            throw new LlmException("No time left for another Voyage call", ErrorCode.LLM_TIMEOUT);
        }
        var configured = Duration.ofSeconds(props.timeoutSeconds());
        return timeLeft.compareTo(configured) < 0 ? timeLeft : configured;
    }

    private List<float[]> attempt(List<String> texts, String inputType, Duration timeout) {
        var request = HttpRequest.newBuilder(URI.create(props.apiUrl()))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body(texts, inputType)))
                .build();
        var response = send(request, timeout);
        if (response.statusCode() != 200) {
            log.warn("Voyage API returned {}: {}", response.statusCode(), excerpt(response.body()));
            var retryAfter = response.headers().firstValue("retry-after");
            throw ProviderErrors.forStatus(PROVIDER, response.statusCode(), retryAfter);
        }
        return vectors(response.body(), texts.size());
    }

    /** The timeout covers reading the whole reply, which the request's own timeout does not. */
    private HttpResponse<byte[]> send(HttpRequest request, Duration timeout) {
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw LlmException.retryable("Voyage API did not answer in time", ErrorCode.LLM_TIMEOUT, null, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw ProviderErrors.unreachable(PROVIDER, io);
            }
            throw new LlmException("Voyage request failed (" + e.getCause().getClass().getSimpleName() + ")",
                    ErrorCode.LLM_ERROR, e);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmException("Request interrupted", ErrorCode.LLM_ERROR, e);
        }
    }

    private String body(List<String> texts, String inputType) {
        var body = json.createObjectNode();
        var input = body.putArray("input");
        texts.forEach(input::add);
        body.put("model", props.model());
        body.put("input_type", inputType);
        body.put("output_dimension", props.dimensions());
        return body.toString();
    }

    /** The vectors of a reply in the order of the texts, each of the configured length. */
    private List<float[]> vectors(byte[] body, int expected) {
        var reply = parse(body);
        var data = reply.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw malformed("it does not hold one embedding for each text");
        }
        var vectors = new float[expected][];
        for (int position = 0; position < expected; position++) {
            var item = data.get(position);
            int index = item.path("index").asInt(position);
            if (index < 0 || index >= expected || vectors[index] != null) {
                throw malformed("an embedding has no place of its own");
            }
            vectors[index] = vector(item.path("embedding"));
        }
        log.debug("Voyage embedded {} texts using {} tokens", expected,
                reply.path("usage").path("total_tokens").asInt());
        return Arrays.asList(vectors);
    }

    private float[] vector(JsonNode embedding) {
        if (!embedding.isArray() || embedding.size() != props.dimensions()) {
            throw malformed("an embedding is not " + props.dimensions() + " numbers long");
        }
        var vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            var number = embedding.get(i);
            if (!number.isNumber() || !Float.isFinite(number.floatValue())) {
                throw malformed("an embedding holds something that is not a number");
            }
            vector[i] = number.floatValue();
        }
        return vector;
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw malformed("it is not JSON");
        }
    }

    /** Never quotes the reply: it may hold text that was sent. */
    private static LlmException malformed(String reason) {
        return new LlmException("Voyage reply is not usable: " + reason, ErrorCode.LLM_ERROR);
    }

    /** What the API said, for the log: without the key should it ever be echoed, and then cut short. */
    private String excerpt(byte[] body) {
        var text = new String(body, StandardCharsets.UTF_8).replace(props.apiKey(), "[key]");
        return text.length() <= LOGGED_ERROR_BODY_CHARS ? text : text.substring(0, LOGGED_ERROR_BODY_CHARS);
    }

    private static boolean isVisibleAscii(int c) {
        return c > ' ' && c < 0x7f;
    }
}
