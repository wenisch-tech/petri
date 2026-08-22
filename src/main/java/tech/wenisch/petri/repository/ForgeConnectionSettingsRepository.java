package tech.wenisch.petri.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.entity.ForgeConnectionSettings;

public interface ForgeConnectionSettingsRepository
        extends JpaRepository<ForgeConnectionSettings, Forge> {
}
