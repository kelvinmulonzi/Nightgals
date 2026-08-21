package com.nightgals.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A city and how many members are browsable in it")
public record CityCountResponse(

        @Schema(example = "Nairobi") String city,

        @Schema(description = "Members in this city, counted over the same population the feed draws from",
                example = "42") long count) {
}
