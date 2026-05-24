package com.chat.chatservice.model;

public record IncomingMessage(
        String type,
        String room,
        String text
) {}
