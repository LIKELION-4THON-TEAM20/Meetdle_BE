package com.likelion4thon.Meetdle.room.dto;

import java.util.List;

/**
 * A24 응답. nextCursor가 있으면 그 값을 cursor로 넣어 다음 페이지를 부른다. 마지막 페이지면 null.
 * 예: {"items":[...20개...],"nextCursor":"djF8QUxMfC..."}
 */
public record MyRoomsPage(List<MyRoomSummary> items, String nextCursor) {}
