package tech.wenisch.petri.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.forge.ForgeProperties;

/**
 * Removes Petri's own secrets from anything an agent said.
 *
 * <p>Necessary because the agent's output is stored on the run and rendered on
 * the card, and an agent that hits a git error will happily quote the remote URL
 * it was given - which, with credential handover switched on, contains a token.
 * A secret pasted into a web page by the tool that was supposed to be guarding
 * against exactly that is not a hypothetical: the whole gate exists because
 * agents put credentials in places nobody chose.
 *
 * <p>This is not a general secret scanner. {@code ChangeInspector} is the one
 * that hunts for credentials it has never seen; this one knows exactly what to
 * look for, and its job is only to make sure Petri does not leak its own.
 */
@Component
public class Redactor {

    private static final String MASK = "[REDACTED]";

    /** Short strings match too much; a token this small is not worth hiding. */
    private static final int MINIMUM_LENGTH = 8;

    private final Set<String> secrets = new LinkedHashSet<>();

    public Redactor(ForgeProperties forges) {
        forges.getForge().values().forEach(instance -> {
            add(instance.getToken());
            add(instance.getAgentToken());
            add(instance.credentialForAgent());
        });
    }

    private void add(String secret) {
        if (secret == null || secret.strip().length() < MINIMUM_LENGTH) {
            return;
        }
        String value = secret.strip();
        secrets.add(value);
        // A token that reached the agent inside a URL comes back percent-encoded,
        // and a literal search would walk straight past it.
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        if (!encoded.equals(value)) {
            secrets.add(encoded);
        }
    }

    /** @return the text with every known secret replaced, or null if it was null */
    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, MASK);
        }
        return redacted;
    }
}
