package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateRoomRequest(String title, @NotNull @Valid DateRange dates,
                                @Min(2) @Max(6) int capacity,
                                @com.fasterxml.jackson.annotation.JsonProperty(required = true)
                                @Min(0) long expectedRevision) {}
