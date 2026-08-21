package tech.wenisch.petri.gateway;

/**
 * Everything the agent needs to begin a turn.
 *
 * @param workspace  directory the agent works in, one per card. Turns in the
 *                   same workspace see each other's commits, which is what lets
 *                   one state commit and a later one push, and what lets cards
 *                   run concurrently without sharing a tree
 * @param repository owner/name on the forge
 * @param cloneUrl   where to clone from, if the workspace is empty
 * @param branch     the branch this card's work belongs on
 * @param prompt     the instruction for this state
 */
public record StartRequest(String workspace, String repository, String cloneUrl,
                           String branch, String prompt) {
}
