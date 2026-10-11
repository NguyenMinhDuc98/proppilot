package dev.proppilot.rag.embed;

import dev.proppilot.text.Folding;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * A deterministic embedding client for tests: a bag of the folded words of the text, hashed into
 * {@value #DIMENSIONS} buckets and scaled to length 1. Texts with the same words get the same vector and texts with
 * words in common are near each other, which is all a search test needs. It never goes near a network.
 *
 * <p>{@code equivalents} maps a word to another, so a test can make two different words mean the same thing to the
 * fake, as a real model would: a question that shares no word with a clause can then still be nearest to it.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    public static final int DIMENSIONS = 1024;

    /** The texts of every call to {@link #embedDocuments}, in order. */
    public final List<List<String>> documentCalls = new CopyOnWriteArrayList<>();
    /** The text of every call to {@link #embedQuery}, in order. */
    public final List<String> queryCalls = new CopyOnWriteArrayList<>();

    private final Map<String, String> equivalents;
    private volatile String model = "fake-embedding-1";
    private volatile boolean enabled = true;
    private volatile IntConsumer beforeDocumentCall = number -> { };
    private volatile RuntimeException queryFailure;

    public FakeEmbeddingClient() {
        this(Map.of());
    }

    public FakeEmbeddingClient(Map<String, String> equivalents) {
        this.equivalents = equivalents;
    }

    /** Runs before each call to {@link #embedDocuments} with the number of the call, from 1; it may throw. */
    public FakeEmbeddingClient beforeDocumentCall(IntConsumer hook) {
        beforeDocumentCall = hook;
        return this;
    }

    /** Makes every call to {@link #embedQuery} throw. */
    public FakeEmbeddingClient failQueriesWith(RuntimeException failure) {
        queryFailure = failure;
        return this;
    }

    public FakeEmbeddingClient useModel(String model) {
        this.model = model;
        return this;
    }

    public FakeEmbeddingClient enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** Back to a client that works, and forgets its calls. For tests that share one instance as a Spring bean. */
    public void reset() {
        documentCalls.clear();
        queryCalls.clear();
        model = "fake-embedding-1";
        enabled = true;
        beforeDocumentCall = number -> { };
        queryFailure = null;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        documentCalls.add(List.copyOf(texts));
        beforeDocumentCall.accept(documentCalls.size());
        return texts.stream().map(this::vector).toList();
    }

    @Override
    public float[] embedQuery(String text) {
        queryCalls.add(text);
        if (queryFailure != null) {
            throw queryFailure;
        }
        return vector(text);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    public float[] vector(String text) {
        var vector = new float[DIMENSIONS];
        var word = new StringBuilder();
        var folded = Folding.fold(Folding.withoutDiacritics(text)) + " ";
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (word.length() > 0) {
                var key = word.toString();
                vector[Math.floorMod(equivalents.getOrDefault(key, key).hashCode(), DIMENSIONS)] += 1;
                word.setLength(0);
            }
        }
        double length = 0;
        for (var value : vector) {
            length += value * value;
        }
        if (length == 0) {
            vector[0] = 1;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) Math.sqrt(length);
        }
        return vector;
    }
}
