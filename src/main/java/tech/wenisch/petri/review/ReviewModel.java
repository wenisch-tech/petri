package tech.wenisch.petri.review;

/**
 * A model asked to judge a change.
 *
 * <p>A port rather than a direct dependency so the verdict gate can be tested
 * without a model, and so the reviewing model is a configuration choice: the
 * point of an independent review is that it need not be the model that wrote
 * the code.
 */
public interface ReviewModel {

    /**
     * @param system   instructions defining the verdict format
     * @param prompt   the task, the diff and whatever the agent said
     * @return the model's reply, verbatim
     */
    String review(String system, String prompt);
}
