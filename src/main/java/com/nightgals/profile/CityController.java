package com.nightgals.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "2. Profile", description = "The cities a profile may be in.")
@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    @Operation(
            summary = "The cities a profile can be in",
            description = """
                    In picker order, biggest first — Douala and Yaoundé lead.

                    Serve the dropdown from this rather than keeping a copy in the client.
                    City is filtered on and counted, so the two lists drifting apart is how
                    a member ends up in a city the browse filters do not offer.

                    `PUT /me/profile` accepts only these, though it is forgiving about how
                    they are spelled: accents and case are ignored, so "yaounde" is stored
                    as "Yaoundé". Anything else is a 400.

                    Public — the signup flow needs it before there is a session.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "City names, in the order to show them")
    @GetMapping
    public List<String> cities() {
        return CameroonCity.all();
    }
}
