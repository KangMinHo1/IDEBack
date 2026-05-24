package com.myide.backend.dto.webrtc;

public enum SignalingType {
    JOIN,
    LEAVE,

    ROOM_USERS,
    USER_JOINED,
    USER_LEFT,

    OFFER,
    ANSWER,
    ICE,

    MUTE,
    UNMUTE,

    CHANNELS,
    CREATE_CHANNEL,
    CHANNEL_CREATED,

    UPDATE_CHANNEL,
    CHANNEL_UPDATED,

    DELETE_CHANNEL,
    CHANNEL_DELETED,

    ERROR
}