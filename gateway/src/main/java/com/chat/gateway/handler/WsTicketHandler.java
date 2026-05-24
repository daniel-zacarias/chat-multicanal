package com.chat.gateway.handler;

import com.chat.gateway.model.ErrorResponse;
import com.chat.gateway.security.JwtService;
import com.chat.gateway.service.WsTicketService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/ws")
public class WsTicketHandler {

    private final WsTicketService wsTicketService;
    private final JwtService jwtService;

    public WsTicketHandler(WsTicketService wsTicketService, JwtService jwtService) {
        this.wsTicketService = wsTicketService;
        this.jwtService = jwtService;
    }

    /**
     * GET /ws/ticket
     * Troca um JWT válido por um ticket de curta duração (30s, uso único) para abertura de conexão WS.
     * O cliente usa o ticket como ?ticket=<uuid> em vez de expor o JWT no query param.
     */
    @GetMapping("/ticket")
    public Mono<ResponseEntity<?>> issueTicket(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(401, "Unauthorized")));
        }

        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(401, "Unauthorized")));
        }

        String userId = jwtService.extractUserId(token);
        return wsTicketService.createTicket(userId)
                .map(ticket -> ResponseEntity.ok((Object) Map.of("ticket", ticket)));
    }
}
