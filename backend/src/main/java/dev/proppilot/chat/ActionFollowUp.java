package dev.proppilot.chat;

import dev.proppilot.actions.ActionService;
import dev.proppilot.agent.DecisionNotice;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the user turn that makes the assistant confirm a person's decision on a proposal. The turn is made from the
 * stored action alone, never from text the client sent, and each decision is confirmed once.
 */
@Component
class ActionFollowUp {

    private final ActionService actions;

    ActionFollowUp(ActionService actions) {
        this.actions = actions;
    }

    /** @throws dev.proppilot.actions.ActionException when the action is unknown, undecided or already confirmed */
    String claim(UUID actionId) {
        var action = actions.claimConfirmation(actionId);
        return new DecisionNotice(action.status(), action.kind(), action.summaryEn(), action.summaryAr(),
                action.decisionReason(), action.result()).text();
    }
}
