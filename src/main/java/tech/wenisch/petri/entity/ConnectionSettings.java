package tech.wenisch.petri.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The operator-editable overlay for the gateway and reviewing-model
 * connections. One row, fixed at id 1.
 *
 * <p>There is no password column. The gateway's credential stays only in
 * whatever holds it today - an environment variable, a mounted Secret - by
 * decision, never here.
 */
@Entity
@Table(name = "connection_settings")
@Getter
@Setter
@NoArgsConstructor
public class ConnectionSettings {

    @Id
    private Long id = 1L;

    @Column(name = "gateway_base_url")
    private String gatewayBaseUrl;

    @Column(name = "gateway_username")
    private String gatewayUsername;

    @Column(name = "gateway_enabled")
    private Boolean gatewayEnabled;

    @Column(name = "review_base_url")
    private String reviewBaseUrl;

    @Column(name = "review_api_key")
    private String reviewApiKey;

    @Column(name = "review_model")
    private String reviewModel;
}
