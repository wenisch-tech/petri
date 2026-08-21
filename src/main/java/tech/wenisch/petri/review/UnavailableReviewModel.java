package tech.wenisch.petri.review;

/** Stands in when no reviewing model is configured. */
public class UnavailableReviewModel implements ReviewModel {

    @Override
    public String review(String system, String prompt) {
        throw new ReviewException("no reviewing model is configured; set petri.review.base-url");
    }
}
