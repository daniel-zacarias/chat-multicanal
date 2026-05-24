package com.chat.chatservice.room;

import com.chat.chatservice.room.dto.CreateRoomRequest;
import com.chat.chatservice.room.dto.RoomResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public Flux<RoomResponse> listRooms() {
        return roomService.getAllRooms();
    }

    @PostMapping
    public Mono<ResponseEntity<RoomResponse>> createRoom(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody @Valid CreateRoomRequest request) {
        return roomService.createRoom(request.name(), userId)
                .map(room -> ResponseEntity.status(HttpStatus.CREATED).body(room));
    }

    @PostMapping("/{id}/join")
    public Mono<ResponseEntity<Void>> joinRoom(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        return roomService.joinRoom(id, userId)
                .map(joined -> joined
                        ? ResponseEntity.<Void>ok().build()
                        : ResponseEntity.<Void>notFound().build());
    }
}
