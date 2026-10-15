package de.grauk.jarvis.conversation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(long conversationId);

    long countByConversationId(long conversationId);

    Optional<Message> findFirstByConversationIdAndRoleOrderByIdDesc(long conversationId, Role role);

    // für status/LatencyRestorer: zuletzt gemessene Latenzen
    Optional<Message> findFirstByRoleAndSttMsNotNullOrderByIdDesc(Role role);

    Optional<Message> findFirstByRoleAndLlmMsNotNullOrderByIdDesc(Role role);

    Optional<Message> findFirstByRoleAndTtsMsNotNullOrderByIdDesc(Role role);
}
