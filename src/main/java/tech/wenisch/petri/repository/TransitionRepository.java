package tech.wenisch.petri.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.Transition;

public interface TransitionRepository extends JpaRepository<Transition, Long> {
    List<Transition> findByCardOrderByIdAsc(Card card);
}
