package com.likelion4thon.Meetdle.room.dto;

import java.util.UUID;

/** 방을 가리키는 응답. 예: {"roomId":"3f2a9c1e-..."} */
public record RoomRef(UUID roomId) {}
