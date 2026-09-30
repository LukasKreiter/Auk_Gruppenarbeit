package de.grauk.jarvis.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, Long> {

    List<MemoryFact> findAllByOrderByCreatedAtDescIdDesc();
}
