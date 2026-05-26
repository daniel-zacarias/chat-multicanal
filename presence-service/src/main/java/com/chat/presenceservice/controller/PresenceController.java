package com.chat.presenceservice.controller;

import com.chat.presenceservice.dto.RoomPresenceResponse;
import com.chat.presenceservice.dto.UserPresenceResponse;
import com.chat.presenceservice.service.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/presence")
public class PresenceController {

    private static final Logger log = LoggerFactory.getLogger(PresenceController.class);

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @PostMapping("/heartbeat")
    public Mono<ResponseEntity<Void>> heartbeat(
            @RequestHeader("X-User-Id") String userId) {
        if (userId == null || userId.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity"));
        }
        log.debug("Heartbeat [userId={}]", userId);
        return presenceService.heartbeat(userId)
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @GetMapping("/room/{roomId}")
    public Mono<RoomPresenceResponse> getRoomPresence(@PathVariable String roomId) {
        return presenceService.roomExists(roomId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.<RoomPresenceResponse>error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Room not found"));
                    }
                    return presenceService.getOnlineRoomMembers(roomId)
                            .map(members -> new RoomPresenceResponse(roomId, members));
                });
    }

    @GetMapping("/user/{userId}")
    public Mono<UserPresenceResponse> getUserPresence(
            @PathVariable String userId,
            @RequestHeader("X-User-Id") String requesterId) {
        if (!requesterId.equals(userId)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Use GET /presence/room/{roomId} to check room members' presence"));
        }
        return presenceService.isOnline(userId)
                .map(online -> new UserPresenceResponse(userId, online));
    }
}
