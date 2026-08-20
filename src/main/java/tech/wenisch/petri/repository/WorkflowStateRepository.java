package tech.wenisch.petri.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.WorkflowState;

public interface WorkflowStateRepository extends JpaRepository<WorkflowState, Long> {
    List<WorkflowState> findByBoardOrderByPositionAsc(Board board);

    Optional<WorkflowState> findByBoardAndName(Board board, String name);
}
