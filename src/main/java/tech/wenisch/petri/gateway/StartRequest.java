package tech.wenisch.petri.gateway;

/**
 * Everything the gateway needs to begin a turn.
 *
 * @param repository owner/name on the forge
 * @param branch     branch the work belongs to; it, not the checkout, owns the
 *                   session, so a new card starts a clean conversation
 * @param prompt     the instruction for this state
 */
public record StartRequest(String repository, String branch, String prompt) {
}
