package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.actions.ActionExecutor.ExecutionContext;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.config.ActionProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The life of a proposed change. The agent proposes, a person approves or rejects, and only an approval runs the
 * executor, once, in one transaction. Proposals that nobody decides expire; that is applied when they are next read.
 */
@Service
public class ActionService {

    static final int MAX_REASON_LENGTH = 200;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** Numbers are the same value in any notation: 1000 repeats a stored 1000.00. */
    private static final Comparator<JsonNode> SAME_VALUE = (stored, sent) -> {
        if (stored instanceof NumericNode a && sent instanceof NumericNode b && !a.isNaN() && !b.isNaN()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return stored.equals(sent) ? 0 : 1;
    };

    private final Map<ActionKind, ActionExecutor<?>> executors;
    private final PendingActionRepository actions;
    private final AuditTrail audit;
    private final ActionLimits limits;
    private final ActionProperties props;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ActionService(List<ActionExecutor<?>> executors, PendingActionRepository actions, AuditTrail audit,
                         ActionLimits limits, ActionProperties props, Clock clock, ObjectMapper json,
                         PlatformTransactionManager transactions) {
        this.executors = executors.stream().collect(Collectors.toMap(ActionExecutor::kind, Function.identity()));
        if (this.executors.size() != ActionKind.values().length) {
            throw new IllegalStateException("Every action kind needs exactly one executor");
        }
        this.actions = actions;
        this.audit = audit;
        this.limits = limits;
        this.props = props;
        this.clock = clock;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Stores a proposal; nothing in the business tables changes.
     *
     * @throws ActionException when the change may not be made, with a code the caller can act on
     */
    public ActionView propose(ActionKind kind, JsonNode rawArgs, UUID runId) {
        var plan = plan(executorFor(kind), rawArgs);
        var now = clock.instant();
        var action = new PendingAction(UUID.randomUUID(), kind, plan, now, now.plus(props.ttl()), runId);
        tx.executeWithoutResult(status -> {
            actions.save(action);
            audit.proposed(action);
        });
        return ActionView.of(action);
    }

    public ActionView get(UUID id) {
        return ActionView.of(current(id));
    }

    public List<ActionView> latest(int limit) {
        actions.findIdsDueForExpiry(clock.instant()).forEach(this::expire);
        return actions.findAllByOrderByCreatedAtDescIdDesc(Limit.of(limit)).stream().map(ActionView::of).toList();
    }

    /** What approving with these edits would do right now. Changes nothing. */
    public PreviewView preview(UUID id, ObjectNode edits) {
        var action = current(id);
        if (action.getStatus() != ActionStatus.PROPOSED) {
            throw alreadyDecided(action);
        }
        return previewOf(executorFor(action.getKind()), action, edits);
    }

    private <A> PreviewView previewOf(ActionExecutor<A> executor, PendingAction action, ObjectNode edits) {
        var plan = describe(executor, validate(executor, withEdits(executor, action.getArgs(), edits)));
        return new PreviewView(action.getId(), action.getKind(), plan.args(), plan.preview(), plan.summaryEn(), plan.summaryAr());
    }

    /**
     * Makes the change. Repeating a call with the same key returns the first outcome and changes nothing; the key is
     * required so a retry after a lost response can never apply a change twice.
     *
     * @param edits values the approver overwrote, limited to what the executor allows
     */
    public ApprovalView approve(UUID id, String idempotencyKey, ObjectNode edits, String actor) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ActionException(ActionError.INVALID_IDEMPOTENCY_KEY,
                    "The Idempotency-Key header is required: 1 to 64 letters, digits, '-' or '_'.");
        }
        Outcome outcome;
        try {
            outcome = tx.execute(status -> approveLocked(id, idempotencyKey, edits, actor));
        } catch (ApprovalFailed failed) {
            // The attempt was rolled back; the failure itself is recorded in a transaction of its own.
            recordFailure(id, idempotencyKey, actor, failed.error());
            throw failed.error();
        } catch (DataIntegrityViolationException conflict) {
            // Another approval committed this key between the up-front check and the write, and the unique index
            // settled it. Anything else is not the caller's doing.
            if (actions.existsByIdempotencyKey(idempotencyKey)) {
                throw keyReused();
            }
            throw conflict;
        }
        return switch (outcome) {
            case Outcome.Decided decided -> new ApprovalView(ActionView.of(decided.action()), decided.replayed());
            case Outcome.Expired ignored -> throw expiredError();
        };
    }

    public ActionView reject(UUID id, String reason, String actor) {
        var cleaned = cleanReason(reason);
        Outcome outcome = tx.execute(status -> {
            var action = lock(id);
            var now = clock.instant();
            if (action.getStatus() == ActionStatus.REJECTED) {
                return new Outcome.Decided(action, true);
            }
            if (action.getStatus() != ActionStatus.PROPOSED) {
                throw alreadyDecided(action);
            }
            if (action.isDueForExpiry(now)) {
                expireLocked(action, now);
                return new Outcome.Expired();
            }
            action.reject(now, actor, cleaned);
            audit.rejected(action, actor);
            return new Outcome.Decided(action, false);
        });
        return switch (outcome) {
            case Outcome.Decided decided -> ActionView.of(decided.action());
            case Outcome.Expired ignored -> throw expiredError();
        };
    }

    private Outcome approveLocked(UUID id, String key, ObjectNode edits, String actor) {
        var action = lock(id);
        var now = clock.instant();
        if (action.getStatus() != ActionStatus.PROPOSED) {
            return settled(action, key);
        }
        if (action.isDueForExpiry(now)) {
            expireLocked(action, now);
            return new Outcome.Expired();
        }
        if (actions.existsByIdempotencyKey(key)) {
            throw keyReused();
        }
        return approveWith(executorFor(action.getKind()), action, key, edits, actor, now);
    }

    private <A> Outcome approveWith(ActionExecutor<A> executor, PendingAction action, String key, ObjectNode edits,
                                    String actor, Instant now) {
        var effective = withEdits(executor, action.getArgs(), edits);
        // Sending back the values the proposal already has changes nothing, so it is not an edit.
        boolean edited = !action.getArgs().equals(SAME_VALUE, effective);
        A args;
        try {
            args = validate(executor, effective);
        } catch (ActionException refused) {
            // A refused edit is a request the person can correct and send again. A proposal that no longer holds is
            // over for good: it fails, and the reason is kept.
            throw edited ? refused : new ApprovalFailed(refused);
        }

        limits.takeApproval();
        try {
            if (edited) {
                var edit = describe(executor, args);
                audit.edited(action, actor, action.getArgs(), edit.args());
                action.edit(edit);
            }
            var execution = executor.execute(args, new ExecutionContext(action.getId(), now));
            action.approve(now, actor, key, execution.result());
            audit.approved(action, actor, execution);
            // Written now rather than at commit, so a key another approval took first fails inside this try and the
            // unit of the daily cap is given back below.
            actions.flush();
            return new Outcome.Decided(action, false);
        } catch (ActionException refused) {
            limits.returnApproval();
            throw new ApprovalFailed(refused);
        } catch (RuntimeException unexpected) {
            limits.returnApproval();
            throw unexpected;
        }
    }

    /** What a repeated call gets for an action that is no longer waiting for a decision. */
    private Outcome settled(PendingAction action, String key) {
        boolean sameKey = key.equals(action.getIdempotencyKey());
        if (action.getStatus() == ActionStatus.APPROVED && sameKey) {
            return new Outcome.Decided(action, true);
        }
        if (action.getStatus() == ActionStatus.FAILED && sameKey) {
            throw new ActionException(ActionError.fromCode(action.getResult().path("code").asText()),
                    action.getDecisionReason());
        }
        throw alreadyDecided(action);
    }

    private void recordFailure(UUID id, String key, String actor, ActionException failure) {
        tx.executeWithoutResult(status -> actions.findForUpdate(id)
                .filter(action -> action.getStatus() == ActionStatus.PROPOSED)
                .ifPresent(action -> {
                    var detail = json.createObjectNode();
                    detail.put("code", failure.error().code());
                    detail.put("message", failure.getMessage());
                    action.fail(clock.instant(), actor, key, detail, failure.getMessage());
                    audit.failed(action, actor);
                }));
    }

    private PendingAction current(UUID id) {
        var action = actions.findById(id).orElseThrow(ActionService::notFound);
        if (!action.isDueForExpiry(clock.instant())) {
            return action;
        }
        expire(id);
        return actions.findById(id).orElseThrow(ActionService::notFound);
    }

    private void expire(UUID id) {
        tx.executeWithoutResult(status -> actions.findForUpdate(id).ifPresent(action -> {
            var now = clock.instant();
            if (action.isDueForExpiry(now)) {
                expireLocked(action, now);
            }
        }));
    }

    private void expireLocked(PendingAction action, Instant now) {
        action.expire(now);
        audit.expired(action);
    }

    private PendingAction lock(UUID id) {
        return actions.findForUpdate(id).orElseThrow(ActionService::notFound);
    }

    private <A> Plan plan(ActionExecutor<A> executor, JsonNode rawArgs) {
        return describe(executor, validate(executor, rawArgs));
    }

    private <A> Plan describe(ActionExecutor<A> executor, A args) {
        var summary = executor.summary(args);
        return new Plan(executor.arguments(args), executor.preview(args), summary.en(), summary.ar());
    }

    private <A> A validate(ActionExecutor<A> executor, JsonNode args) {
        try {
            return executor.validate(args);
        } catch (ToolInputException malformed) {
            throw new ActionException(ActionError.INVALID_ARGUMENT, malformed.getMessage());
        }
    }

    private JsonNode withEdits(ActionExecutor<?> executor, JsonNode args, ObjectNode edits) {
        if (edits == null || edits.isEmpty()) {
            return args;
        }
        edits.fieldNames().forEachRemaining(name -> {
            if (!executor.editableFields().contains(name)) {
                throw new ActionException(ActionError.INVALID_ARGUMENT, name + " cannot be changed when approving.");
            }
        });
        ObjectNode merged = args.deepCopy();
        return merged.setAll(edits);
    }

    private ActionExecutor<?> executorFor(ActionKind kind) {
        return executors.get(kind);
    }

    private static String cleanReason(String reason) {
        if (reason == null) {
            return null;
        }
        var cleaned = PlainText.clean(reason);
        if (cleaned.length() > MAX_REASON_LENGTH) {
            throw new ActionException(ActionError.INVALID_ARGUMENT,
                    "reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static ActionException keyReused() {
        return new ActionException(ActionError.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for another action.");
    }

    private static ActionException notFound() {
        return new ActionException(ActionError.ACTION_NOT_FOUND, "There is no such action.");
    }

    private static ActionException expiredError() {
        return new ActionException(ActionError.ACTION_EXPIRED, "This proposal expired before anyone decided on it.");
    }

    private static ActionException alreadyDecided(PendingAction action) {
        if (action.getStatus() == ActionStatus.EXPIRED) {
            return expiredError();
        }
        return new ActionException(ActionError.ACTION_ALREADY_DECIDED,
                "This action was already decided: " + action.getStatus().name().toLowerCase() + ".");
    }

    private sealed interface Outcome {

        record Decided(PendingAction action, boolean replayed) implements Outcome {
        }

        record Expired() implements Outcome {
        }
    }

    /** An approval that could not be made. Rolls the attempt back; the cause is what the caller is told. */
    private static final class ApprovalFailed extends RuntimeException {

        ApprovalFailed(ActionException cause) {
            super(cause);
        }

        ActionException error() {
            return (ActionException) getCause();
        }
    }
}
