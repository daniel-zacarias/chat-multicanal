package com.chat.chatservice.model;

public record OutgoingMessage(
        String type,
        String from,
        String fromUsername,
        String room,
        String text,
        String timestamp
) {}
