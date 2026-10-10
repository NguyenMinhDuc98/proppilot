package dev.proppilot.chat;

import dev.proppilot.actions.ActionService;
import dev.proppilot.agent.DecisionNotice;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the user turn that makes the assistant confirm a person's decision on a proposal. The turn is made from the
 * stored action alone, never from text the client sent. Each decision is confirmed once, and only for the person who
 * decided; a confirmation that did not reach them is given back.
 */
@Component
class ActionFollowUp {

    private final ActionService actions;

    ActionFollowUp(ActionService actions) {
        this.actions = actions;
    }

    /**
     * @param visitor who is asking: it has to be the visitor who decided
     * @throws dev.proppilot.actions.ActionException when the action is unknown, undecided, decided by someone else or
     *                                               already confirmed
     */
    String claim(UUID actionId, String visitor) {
        var action = actions.claimConfirmation(actionId, visitor);
        return new DecisionNotice(action.status(), action.kind(), action.summaryEn(), action.summaryAr(),
                action.decisionReason(), action.result()).text();
    }

    void release(UUID actionId) {
        actions.releaseConfirmation(actionId);
    }
}
