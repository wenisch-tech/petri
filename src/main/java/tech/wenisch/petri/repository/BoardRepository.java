package tech.wenisch.petri.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.petri.entity.Board;

public interface BoardRepository extends JpaRepository<Board, Long> {
    Optional<Board> findBySlug(String slug);

    List<Board> findByEnabledTrue();
}
