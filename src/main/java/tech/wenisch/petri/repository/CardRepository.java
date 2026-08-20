package tech.wenisch.petri.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.WorkflowState;

public interface CardRepository extends JpaRepository<Card, Long> {
    List<Card> findByBoardOrderByIdAsc(Board board);

    List<Card> findByState(WorkflowState state);

    long countByState(WorkflowState state);
}
