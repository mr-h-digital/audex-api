package za.co.mrhdigital.audex.auth;

import java.util.UUID;

/** The signed-in person making a request, plus the session they are using. */
public record AuthUser(UUID id, String username, String displayName, UUID sessionId) {}
