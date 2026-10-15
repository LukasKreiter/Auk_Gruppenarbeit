package de.grauk.jarvis.conversation;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallRepository extends JpaRepository<ToolCall, Long> {

    List<ToolCall> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);
}
