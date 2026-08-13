package com.nightgals.live.dto;

import com.nightgals.live.StreamProvider;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        How to connect to a broadcast. What is filled in depends on the provider
        this deployment uses, so read `mode` first and ignore the rest.""")
public record StreamCredentialsResponse(

        @Schema(description = """
                `WEBRTC` - connect to `url` with `token` and join `room`.
                `HLS` - just play `url`.
                `RTMP` - publishing only: push to `ingestUrl` with `streamKey`.
                """, example = "WEBRTC")
        StreamProvider.Mode mode,

        @Schema(description = "Where to connect. A `wss://` host for WebRTC, the media URL for HLS.",
                example = "wss://nightgals-abc123.livekit.cloud")
        String url,

        @Schema(description = """
                Short-lived and specific to you. Minted per request after the access
                check, so it expires rather than becoming a link anybody can pass on.
                Absent when the provider has no notion of one.""")
        String token,

        @Schema(description = "Which broadcast to join. WebRTC providers only.",
                example = "live-890c4187-f45c-4c36-9f72-bb76b629201e")
        String room,

        @Schema(description = "Where to push RTMP. Publishing only, and only when the provider takes it.")
        String ingestUrl,

        @Schema(description = "Pairs with `ingestUrl`. Secret - never returned to a viewer.")
        String streamKey) {

    public static StreamCredentialsResponse of(StreamProvider.Credentials credentials) {
        return new StreamCredentialsResponse(
                credentials.mode(),
                credentials.url(),
                credentials.token(),
                credentials.room(),
                credentials.ingestUrl(),
                credentials.streamKey());
    }
}
