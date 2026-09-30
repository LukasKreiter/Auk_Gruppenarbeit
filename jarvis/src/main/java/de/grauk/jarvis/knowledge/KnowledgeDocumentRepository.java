package de.grauk.jarvis.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findAllByStatus(KnowledgeDocument.Status status);

    List<KnowledgeDocument> findAllByOrderByCreatedAtDescIdDesc();
}
