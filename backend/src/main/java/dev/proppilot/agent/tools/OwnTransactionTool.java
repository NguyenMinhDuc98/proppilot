package dev.proppilot.agent.tools;

/**
 * A tool that may wait for a provider over the network, such as the embedding of a question. The registry does not wrap
 * it in its read-only transaction, because that would keep a database connection while the provider is waited for and
 * the pool is small. The tool reads through short transactions of its own and, like every tool that is not a
 * {@link ProposalTool}, changes nothing.
 */
public interface OwnTransactionTool extends Tool {
}
