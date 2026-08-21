package tech.wenisch.petri.forge;

/** One path touched by a range of commits, and what happened to it. */
public record ChangedFile(String path, String status) {
}
