package com.chat.historyservice.service;

import com.chat.historyservice.dto.MessageRequest;
import com.chat.historyservice.dto.MessageResponse;
import com.chat.historyservice.dto.PageResponse;
import com.chat.historyservice.model.Message;
import com.chat.historyservice.model.MessageKey;
import com.chat.historyservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final MessageRepository messageRepository;

    public Mono<PageResponse> getHistory(String roomId, Instant before, int limit) {
        Flux<Message> messages = before != null
                ? messageRepository.findByRoomIdBefore(roomId, before, limit)
                : messageRepository.findLatestByRoomId(roomId, limit);

        return messages.collectList().map(list -> {
            List<MessageResponse> responses = list.stream().map(MessageResponse::from).toList();
            Instant nextBefore = list.isEmpty() ? null : list.getLast().getKey().getCreatedAt();
            return new PageResponse(responses, nextBefore);
        });
    }

    public Mono<MessageResponse> saveMessage(String userId, MessageRequest request) {
        Message message = new Message(
                new MessageKey(request.roomId(), Instant.now(), UUID.randomUUID()),
                userId,
                request.content()
        );
        return messageRepository.save(message).map(MessageResponse::from);
    }
}
