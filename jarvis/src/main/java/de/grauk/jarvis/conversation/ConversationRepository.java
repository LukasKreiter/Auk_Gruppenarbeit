package de.grauk.jarvis.conversation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findAllByOrderByUpdatedAtDescIdDesc();
}
