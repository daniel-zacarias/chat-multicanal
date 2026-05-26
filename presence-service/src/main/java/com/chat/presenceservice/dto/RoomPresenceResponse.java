package com.chat.presenceservice.dto;

import java.util.List;

public record RoomPresenceResponse(String roomId, List<String> onlineUsers) {}
