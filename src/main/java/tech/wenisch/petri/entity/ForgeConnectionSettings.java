package tech.wenisch.petri.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The operator-editable overlay for one forge's connection, keyed by forge
 * type rather than a generated id - there is at most one override per forge.
 *
 * <p>A row can exist with nothing matching it in {@code petri.forge.*} at all.
 * Adding a forge from the Connections screen on a fresh install, with nothing
 * set in the environment, is meant to work exactly like adding one there.
 */
@Entity
@Table(name = "forge_connection_settings")
@Getter
@Setter
@NoArgsConstructor
public class ForgeConnectionSettings {

    @Id
    @Enumerated(EnumType.STRING)
    private Forge forge;

    @Column(name = "base_url")
    private String baseUrl;

    private String token;

    @Column(name = "hand_token_to_agent")
    private Boolean handTokenToAgent;

    @Column(name = "agent_token")
    private String agentToken;

    @Column(name = "agent_username")
    private String agentUsername;
}
