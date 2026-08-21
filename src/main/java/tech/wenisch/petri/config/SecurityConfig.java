package tech.wenisch.petri.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Who may read the board and who may queue work.
 *
 * <p>Two audiences, two mechanisms. People sign in to look at the board; the API
 * carries a bearer token, because the thing on the other end of it is a script.
 *
 * <p>Nothing here is permissive by default. An unset API key does not mean "no
 * authentication required", it means the write API is unusable - a system that
 * starts agent runs against real repositories should refuse rather than open
 * itself while somebody gets round to configuring it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${petri.security.username:admin}") String username,
                             @Value("${petri.security.password:}") String password,
                             PasswordEncoder encoder) {
        if (password.isBlank()) {
            // A generated password beats a well-known default: an install nobody
            // configured should be inconvenient, not open.
            password = java.util.UUID.randomUUID().toString();
            LOG.warn("No petri.security.password set. Generated one for this run only: {}", password);
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("VIEWER")
                .build());
    }

    /**
     * The API: bearer token, stateless, no login page.
     *
     * <p>Separated from the browser chain so a script never gets redirected to a
     * form and a browser session can never authorise a write.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain apiChain(HttpSecurity http,
                                 @Value("${petri.security.api-key:}") String apiKey)
            throws Exception {
        if (apiKey.isBlank()) {
            LOG.warn("No petri.security.api-key set; the write API will reject every request");
        }

        return http.securityMatcher("/api/**")
                // Safe to disable only because this chain accepts no cookie and
                // no session: a bearer token cannot be sent by a browser that
                // merely visited a hostile page.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKey),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("API"))
                // 401, not 403. A caller with no token or a wrong one has not
                // been forbidden something it was recognised for; it was never
                // authenticated, and the difference matters to whatever is
                // deciding whether to retry with better credentials.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** The board and everything around it: a person, signed in. */
    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // Probes must answer before anyone has signed in, or
                        // Kubernetes cannot tell a starting pod from a broken one.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Prometheus scrapes without credentials, and the chart
                        // ships a ServiceMonitor pointing here; behind the login
                        // form it would collect redirects instead of metrics.
                        // These figures are counts and timings, not card content,
                        // and the endpoint should be reachable only from inside
                        // the cluster - keep it off any public ingress.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/css/**", "/webjars/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.permitAll())
                .logout(logout -> logout.permitAll())
                .build();
    }
}
