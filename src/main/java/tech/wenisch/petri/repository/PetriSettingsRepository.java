package tech.wenisch.petri.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.PetriSettings;

public interface PetriSettingsRepository extends JpaRepository<PetriSettings, Long> {
}
