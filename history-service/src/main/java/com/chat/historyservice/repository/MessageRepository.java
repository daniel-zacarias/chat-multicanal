package com.chat.historyservice.repository;

import com.chat.historyservice.model.Message;
import com.chat.historyservice.model.MessageKey;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;

public interface MessageRepository extends ReactiveCassandraRepository<Message, MessageKey> {

    @Query("SELECT * FROM messages WHERE room_id = ?0 LIMIT ?1")
    Flux<Message> findLatestByRoomId(String roomId, int limit);

    @Query("SELECT * FROM messages WHERE room_id = ?0 AND created_at < ?1 LIMIT ?2")
    Flux<Message> findByRoomIdBefore(String roomId, Instant before, int limit);
}
