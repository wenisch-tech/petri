package tech.wenisch.petri.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.ConnectionSettings;

public interface ConnectionSettingsRepository extends JpaRepository<ConnectionSettings, Long> {
}
