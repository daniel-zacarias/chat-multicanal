package com.chat.chatservice.room;

import com.chat.chatservice.room.dto.CreateRoomRequest;
import com.chat.chatservice.room.dto.MemberResponse;
import com.chat.chatservice.room.dto.RoomResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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
    public Flux<RoomResponse> listRooms(@RequestHeader("X-User-Id") String userId) {
        return roomService.getRoomsForMember(userId);
    }

    @PostMapping
    public Mono<ResponseEntity<RoomResponse>> createRoom(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody @Valid CreateRoomRequest request) {
        return roomService.createRoom(request.name(), userId)
                .map(room -> ResponseEntity.status(HttpStatus.CREATED).body(room));
    }

    @GetMapping("/{id}/members")
    public Flux<MemberResponse> listMembers(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {
        return roomService.isMember(id, userId)
                .flatMapMany(isMember -> {
                    if (!isMember) {
                        return Flux.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this room"));
                    }
                    return roomService.getMembers(id, userId);
                });
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
