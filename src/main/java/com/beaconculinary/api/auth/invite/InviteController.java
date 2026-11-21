package com.beaconculinary.api.auth.invite;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T0-6a: public invite endpoints (no sign-in). The token travels only in the request body: a path or
 * query would put it in the request log. Under the /auth/** burst limit and login protection
 * (method INVITE). Accepting never signs anyone in.
 */
@RestController
@RequestMapping("/auth/invites")
public class InviteController {
    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/lookup")
    public ResponseEntity<InviteDtos.LookupResponse> lookup(@RequestBody(required = false) InviteDtos.TokenRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inviteService.lookup(body == null ? null : body.token()));
    }

    @PostMapping("/accept")
    public ResponseEntity<InviteDtos.AcceptResponse> accept(@RequestBody(required = false) InviteDtos.AcceptRequest body) {
        var request = body == null ? new InviteDtos.AcceptRequest(null, null, null) : body;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inviteService.accept(request.token(), request.password(), request.pin()));
    }
}
