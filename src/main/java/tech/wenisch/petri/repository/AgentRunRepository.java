package tech.wenisch.petri.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.RunStatus;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    List<AgentRun> findByCardOrderByIdDesc(Card card);

    Optional<AgentRun> findFirstByCardOrderByIdDesc(Card card);

    /** Runs the liveness poller has to keep watching. */
    List<AgentRun> findByStatusIn(List<RunStatus> statuses);
}
